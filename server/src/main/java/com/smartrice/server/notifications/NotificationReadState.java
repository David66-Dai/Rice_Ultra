package com.smartrice.server.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_read_state")
public class NotificationReadState {

	@Id
	@Column(name = "user_id")
	private Long userId;
	@Column(name = "through_id", nullable = false)
	private long throughId;

	protected NotificationReadState() {
	}

	public NotificationReadState(Long userId, long throughId) {
		this.userId = userId;
		this.throughId = throughId;
	}

	public long getThroughId() {
		return throughId;
	}
}
