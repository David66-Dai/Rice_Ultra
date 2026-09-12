package com.smartrice.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartrice.server.auth.AuthException;
import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.auth.UserAccountRepository;
import com.smartrice.server.notifications.NotificationEventRepository;
import com.smartrice.server.notifications.NotificationReadStateRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class DeviceActivityServiceTests {

	private final UserAccountRepository users = mock(UserAccountRepository.class);
	private final NotificationEventRepository events = mock(NotificationEventRepository.class);
	private final NotificationReadStateRepository reads = mock(NotificationReadStateRepository.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private final Map<String, StationDevice> rows = new HashMap<>();
	private final StationDeviceRepository deviceRepository = mock(StationDeviceRepository.class);
	@SuppressWarnings("unchecked")
	private final ObjectProvider<DeviceActuator> provider = mock(ObjectProvider.class);
	private final DeviceActuator serial = mock(DeviceActuator.class);
	private final DeviceCommandService commands = new DeviceCommandService(deviceRepository, provider);
	private final DevicesProperties permissions = new DevicesProperties();
	private final Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("stale-name")
		.claim("uid", 1L).expiresAt(Instant.now().plusSeconds(60)).build();
	private DeviceActivityService activity;

	@BeforeEach
	void setUp() {
		UserAccount user = mock(UserAccount.class);
		when(user.getId()).thenReturn(1L);
		when(user.getUsername()).thenReturn("operator");
		when(user.getDisplayName()).thenReturn("操作员");
		when(user.isEnabled()).thenReturn(true);
		when(users.findById(1L)).thenReturn(Optional.of(user));
		when(reads.findById(1L)).thenReturn(Optional.empty());
		when(events.findTop100ByOrderByIdDesc()).thenReturn(List.of());
		rows.clear();
		when(deviceRepository.findByStationIdAndDevice(any(), any())).thenAnswer(
			invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))));
		when(deviceRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			StationDevice row = invocation.getArgument(0);
			rows.put(row.getStationId() + ":" + row.getDevice(), row);
			return row;
		});
		when(provider.getIfAvailable()).thenReturn(serial);
		when(serial.isAvailable()).thenReturn(true);
		when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		permissions.setControlUsers(List.of("operator"));
		activity = new DeviceActivityService(users, permissions, commands, events, reads, transactions);
	}

	@Test
	void freshServerStateIsUnknownAndAllowlistUsesCurrentDatabaseUsername() {
		DeviceSyncResponse response = snapshot();
		assertThat(response.canControl()).isTrue();
		assertThat(response.devices()).allSatisfy(state -> {
			assertThat(state.enabled()).isNull();
			assertThat(state.revision()).isZero();
			assertThat(state.updatedAt()).isNull();
		});
		permissions.setControlUsers(List.of("stale-name"));
		assertThat(snapshot().canControl()).isFalse();
	}

	@Test
	void missingCollectorStillDeniesUnauthorizedFirstAndReturns503ForAllowedUser() {
		when(provider.getIfAvailable()).thenReturn(null);
		permissions.setControlUsers(List.of());
		assertThatThrownBy(() -> activity.control(jwt, command()))
			.isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		permissions.setControlUsers(List.of("operator"));
		assertThatThrownBy(() -> activity.control(jwt, command()))
			.isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
		assertThat(snapshot().available()).isFalse();
		verify(serial, never()).sendCommand(anyInt());
	}

	@Test
	void brokenNotificationStoragePreventsSerialWrite() {
		when(events.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("test-only unavailable"));
		assertThatThrownBy(() -> activity.control(jwt, command())).isInstanceOf(ResponseStatusException.class);
		verify(serial, never()).sendCommand(anyInt());
		assertThat(snapshot().devices().getFirst().revision()).isZero();
	}

	@Test
	void databaseCommitFailureAfterSerialWriteBroadcastsUncertainty() {
		DeviceSyncResponse before = snapshot();
		var waiting = activity.sync(jwt, before.cursor(), 25);
		doThrow(new TransactionSystemException("test-only commit failure")).when(transactions).commit(any());
		assertThatThrownBy(() -> activity.control(jwt, command())).isInstanceOf(ResponseStatusException.class);
		verify(serial).sendCommand(0x01);
		DeviceSyncResponse after = (DeviceSyncResponse) waiting.getResult();
		assertThat(after).isNotNull();
		assertThat(after.devices().getFirst().enabled()).isNull();
		assertThat(after.devices().getFirst().revision()).isEqualTo(1);
		assertThat(after.cursor()).isNotEqualTo(before.cursor());
	}

	@Test
	void scheduledStopCanRetryItsOwnUncertainWriteWithoutRestoringUserPermission() {
		DeviceControlResponse started = activity.control(jwt, command());
		permissions.setControlUsers(List.of());
		doThrow(new IllegalStateException("test-only partial stop")).doNothing()
			.when(serial).sendCommand(0x03);
		DeviceControlRequest stop = new DeviceControlRequest("S01", "pump", false, started.state().revision());
		assertThatThrownBy(() -> activity.automaticStop("operator", "操作员", stop))
			.isInstanceOf(ResponseStatusException.class);
		DeviceControlResponse retried = activity.automaticStop("operator", "操作员", stop);
		assertThat(retried.state().enabled()).isFalse();
		assertThat(retried.state().revision()).isEqualTo(started.state().revision() + 2);
		verify(serial).sendCommand(0x01);
		verify(serial, org.mockito.Mockito.times(2)).sendCommand(0x03);
	}

	@Test
	void expiredTokenCannotReadOrControlAndWaitingTokenIsRechecked() {
		Jwt expired = Jwt.withTokenValue("test").header("alg", "HS256").claim("uid", 1L)
			.expiresAt(Instant.now().minusSeconds(1)).build();
		assertThatThrownBy(() -> activity.sync(expired, null, 0)).isInstanceOf(AuthException.class);
		assertThatThrownBy(() -> activity.markRead(expired, 0)).isInstanceOf(AuthException.class);
		assertThatThrownBy(() -> activity.control(expired, command())).isInstanceOf(AuthException.class);
		verify(serial, never()).sendCommand(anyInt());
	}

	@Test
	void yamlListBindsWithDefaultDenyAndCaseInsensitiveUsernames() {
		DevicesProperties empty = new DevicesProperties();
		assertThat(empty.permits("admin")).isFalse();
		DevicesProperties bound = new Binder(new MapConfigurationPropertySource(Map.of(
			"app.devices.control-users[0]", "operator", "app.devices.control-users[1]", "second")))
			.bind("app.devices", Bindable.of(DevicesProperties.class)).orElseThrow(IllegalStateException::new);
		assertThat(bound.permits("OPERATOR")).isTrue();
		assertThat(bound.permits("second")).isTrue();
		assertThat(bound.permits("admin")).isFalse();
	}

	private DeviceSyncResponse snapshot() {
		return (DeviceSyncResponse) activity.sync(jwt, null, 0).getResult();
	}

	private DeviceControlRequest command() {
		return new DeviceControlRequest("S01", "pump", true, 0L);
	}
}
