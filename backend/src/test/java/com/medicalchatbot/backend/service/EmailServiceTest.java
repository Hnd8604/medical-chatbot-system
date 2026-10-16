package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.medicalchatbot.backend.config.properties.PasswordResetProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    @Mock
    private JavaMailSender mailSender;

    /** Collects the background tasks instead of running them, so the test decides when they run. */
    private final List<Runnable> backgroundTasks = new ArrayList<>();
    private EmailService service;

    @BeforeEach
    void setUp() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        service = new EmailService(mailSenderProvider,
                new PasswordResetProperties(10, 10, 5, 60, "no-reply@demo.local", "Medical Chatbot"),
                backgroundTasks::add);
    }

    @Test
    void theCodeIsSentInTheBackgroundSoTheCallerDoesNotWaitForSmtp() {
        service.sendPasswordResetCode("user@demo.local", "123456");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertEquals(1, backgroundTasks.size());

        backgroundTasks.getFirst().run();
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertEquals("user@demo.local", message.getValue().getTo()[0]);
    }

    @Test
    void aDeliveryFailureIsOnlyLogged() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        service.sendPasswordResetCode("user@demo.local", "123456");

        backgroundTasks.getFirst().run();
    }
}
