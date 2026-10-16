package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.response.notification.NotificationResponse;
import com.medicalchatbot.backend.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface NotificationMapper {

    @Mapping(target = "isRead", source = "read")
    NotificationResponse toNotificationResponse(Notification notification);
}
