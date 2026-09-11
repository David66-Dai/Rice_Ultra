package com.smartrice.server.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationReadStateRepository extends JpaRepository<NotificationReadState, Long> {
}
