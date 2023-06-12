package com.uci.adapter.firebase.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import com.uci.adapter.firebase.web.inbound.FirebaseWebMessage;
import com.uci.adapter.firebase.web.inbound.FirebaseWebReport;
import com.uci.adapter.provider.factory.AbstractProvider;
import com.uci.adapter.provider.factory.IProvider;
import com.uci.utils.BotService;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

@Slf4j
@Getter
@Setter
@Builder
public class FirebaseNotificationAdapter extends AbstractProvider implements IProvider {
    @Autowired
    public BotService botService;

    private String notificationKeyEnable;

    /**
     * Convert Firebase Message Object to XMessage Object
     *
     * @param message
     * @return
     * @throws JAXBException
     * @throws JsonProcessingException
     */
    public Mono<XMessage> convertMessageToXMsg(Object message) throws JAXBException, JsonProcessingException {
        FirebaseWebMessage webMessage = (FirebaseWebMessage) message;
        SenderReceiverInfo from = SenderReceiverInfo.builder().deviceType(DeviceType.PHONE_FCM).build();
        SenderReceiverInfo to = SenderReceiverInfo.builder().userID("admin").build();
        XMessage.MessageState messageState = XMessage.MessageState.REPLIED;
        MessageId messageIdentifier = MessageId.builder().build();
        String eventType = webMessage.getEventType();

        XMessagePayload xmsgPayload = XMessagePayload.builder().build();
        xmsgPayload.setText(webMessage.getText());
        XMessage.MessageType messageType = XMessage.MessageType.TEXT;

        /* To use later in outbound reply message's message id & to */
        /* Message state changed by event type */
        if (eventType != null && (eventType.equals("DELIVERED")
                || eventType.equals("READ")) && webMessage.getReport() != null) {
            FirebaseWebReport reportMsg = webMessage.getReport();
            messageState = getMessageState(eventType);
            messageIdentifier.setChannelMessageId(reportMsg.getExternalId());
            from.setUserID(reportMsg.getDestAdd());
            if (reportMsg.getFcmDestAdd() != null) {
                Map<String, String> meta = new HashMap();
                meta.put("fcmToken", reportMsg.getFcmDestAdd());
                from.setMeta(meta);
            }
        } else {
            messageIdentifier.setChannelMessageId(webMessage.getMessageId());
            messageIdentifier.setReplyId(webMessage.getFrom());
            from.setUserID(webMessage.getFrom());
        }

        XMessage x = XMessage.builder()
                .to(to)
                .from(from)
                .channelURI("web")
                .providerURI("firebase")
                .messageState(messageState)
                .messageId(messageIdentifier)
                .messageType(messageType)
                .timestamp(Timestamp.valueOf(LocalDateTime.now()).getTime())
                .payload(xmsgPayload).build();
        log.info("Current message :: " + x.toString());
        return Mono.just(x);
    }

    @NotNull
    public static XMessage.MessageState getMessageState(String eventType) {
        XMessage.MessageState messageState;
        switch (eventType) {
            case "SENT":
                messageState = XMessage.MessageState.SENT;
                break;
            case "DELIVERED":
                messageState = XMessage.MessageState.DELIVERED;
                break;
            case "READ":
                messageState = XMessage.MessageState.READ;
                break;
            default:
                messageState = XMessage.MessageState.FAILED_TO_DELIVER;
                //TODO: Save the state of message and reason in this case.
                break;
        }
        return messageState;
    }

    /**
     * Process XMessage & send firebase notification
     *
     * @param nextMsg
     * @return
     * @throws Exception
     */
    @Override
    public Mono<XMessage> processOutBoundMessageF(XMessage nextMsg) {
        try {
            SenderReceiverInfo to = nextMsg.getTo();
            XMessagePayload payload = nextMsg.getPayload();
            Map<String, String> data = new HashMap<>();
            for (Data dataArrayList : payload.getData()) {
                data.put(dataArrayList.getKey(), dataArrayList.getValue());
            }
            if (data != null && data.get("fcmToken") != null) {
                return botService.getAdapterCredentials(nextMsg.getAdapterId()).map(new Function<JsonNode, Mono<XMessage>>() {
                    @Override
                    public Mono<XMessage> apply(JsonNode credentials) {
                        String channelMessageId = UUID.randomUUID().toString();
                        log.info("credentials: " + credentials);
                        if (credentials != null && credentials.path("serviceKey") != null
                                && !credentials.path("serviceKey").asText().isEmpty()) {
                            String click_action = null;
                            if (data.get("fcmClickActionUrl") != null && !data.get("fcmClickActionUrl").isEmpty()) {
                                click_action = data.get("fcmClickActionUrl");
                            }


                            return (new FirebaseNotificationService()).sendNotificationMessage(credentials.path("serviceKey").asText(), data.get("fcmToken"), nextMsg.getPayload().getTitle(), nextMsg.getPayload().getText(), click_action, nextMsg.getTo().getUserID(), channelMessageId, notificationKeyEnable, data)
                                    .map(new Function<Boolean, XMessage>() {
                                        @Override
                                        public XMessage apply(Boolean result) {
                                            if (result) {
                                                nextMsg.setTo(to);
                                                nextMsg.setMessageId(MessageId.builder().channelMessageId(channelMessageId).build());
                                                nextMsg.setMessageState(XMessage.MessageState.SENT);
                                            }
                                            return nextMsg;
                                        }
                                    });
                        }
                        return null;
                    }
                }).flatMap(new Function<Mono<XMessage>, Mono<? extends XMessage>>() {
                    @Override
                    public Mono<? extends XMessage> apply(Mono<XMessage> n) {
                        log.info("Mono FlatMap Level 2");
                        return n;
                    }
                });

            }
        } catch (Exception ex) {
            log.error("FirebaseNotificationAdapter:processOutBoundMessageF::Exception: " + ex.getMessage());
        }
        return null;
    }

    @Override
    public Mono<List<XMessage>> processOutBoundMessageF(Mono<List<XMessage>> xMessageList) throws Exception {
        List<XMessage> xMessageListCass = new ArrayList<XMessage>();
        Map<String, List<Message>> adapterIdMap = new HashMap<>();
        Set<String> uniqueUserSet = new HashSet<>();
        xMessageList
                .flatMapMany(Flux::fromIterable)
                .flatMap(nextMsg -> {
                            SenderReceiverInfo to = nextMsg.getTo();
                            XMessagePayload payload = nextMsg.getPayload();
                            Map<String, String> data = new HashMap<>();
                            for (Data dataArrayList : payload.getData()) {
                                data.put(dataArrayList.getKey(), dataArrayList.getValue());
                            }
                            if (data != null && data.get("fcmToken") != null) {
                                String channelMessageId = UUID.randomUUID().toString();
                                String click_action = null;
                                if (data.get("fcmClickActionUrl") != null && !data.get("fcmClickActionUrl").isEmpty()) {
                                    click_action = data.get("fcmClickActionUrl");
                                }
                                Message message = Message.builder()
                                        .setNotification(Notification.builder()
                                                .setTitle(nextMsg.getPayload().getTitle())
                                                .setBody(nextMsg.getPayload().getText())
                                                .build())
                                        .setToken(data.get("fcmToken"))
                                        .putData("body", nextMsg.getPayload().getText())
                                        .putData("title", nextMsg.getPayload().getTitle())
                                        .putData("externalId", channelMessageId)
                                        .putData("destAdd", nextMsg.getTo().getUserID())
                                        .putData("fcmDestAdd", data.get("fcmToken"))
                                        .putData("click_action", click_action)
                                        .build();
                                uniqueUserSet.add(nextMsg.getTo().getUserID());
                                String adapterId = nextMsg.getAdapterId();

                                if (adapterId != null) {
                                    List<Message> tempMsgList = adapterIdMap.computeIfAbsent(adapterId, k -> new ArrayList<>());
                                    tempMsgList.add(message);
                                    adapterIdMap.put(adapterId, tempMsgList);
                                } else {
                                    log.error("AdapterId not found : " + nextMsg);
                                }
                                nextMsg.setTo(to);
                                nextMsg.setMessageId(MessageId.builder().channelMessageId(channelMessageId).build());
                                nextMsg.setMessageState(XMessage.MessageState.SENT);
                                xMessageListCass.add(nextMsg);
                                return Mono.just(nextMsg);
                            } else {
                                return Mono.just(nextMsg);
                            }
                        }
                )
                .doOnComplete(() -> {
                    log.info("FirebaseNotificationAdapter:adapterIdMap: " + adapterIdMap.keySet());
                    adapterIdMap.keySet().forEach(adapterId -> {
                        botService.getAdapterCredentials(adapterId)
                                .doOnNext(credentials -> {
                                    if (credentials != null && credentials.path("serviceKey") != null) {
                                        log.info("service key data: " + credentials.path("serviceKey"));
                                        String serviceKey = credentials.path("serviceKey").toString();
                                        try {
                                            FirebaseMessaging firebaseMessaging = configureFirebaseMessaging(serviceKey, adapterId);
                                            List<Message> messageList = adapterIdMap.get(adapterId);
                                            if (messageList != null && !messageList.isEmpty() && firebaseMessaging != null) {
                                                log.info("All messages processed: messageList count: " + messageList.size());
                                                try {
                                                    BatchResponse response = firebaseMessaging.sendAll(messageList);
                                                    response.getResponses().forEach(sendResponse -> {
                                                        if (sendResponse.isSuccessful()) {
                                                            log.info("FirebaseNotificationService: Notification triggered success: Message Id : " + sendResponse.getMessageId());
                                                        } else if (sendResponse.getException() != null) {
                                                            log.error("FirebaseNotificationService: Notification not sent : Exception : " + sendResponse.getException());
                                                        } else {
                                                            log.info("FirebaseNotificationService: All SendResponse messageId : " + sendResponse.getMessageId() + " Exception : " + sendResponse.getException());
                                                        }
                                                    });
                                                    log.info("Notification:: Success: " + response.getSuccessCount() + " Failed: " + response.getFailureCount() + " --> Total Unique users : " + uniqueUserSet.size());
                                                } catch (Exception ex) {
                                                    log.error("An Error occurred: " + ex.getMessage());
                                                }
                                            } else {
                                                log.error("FirebaseNotificationAdapter:processOutBoundMessageF:: MessageList empty :" + messageList + " or firebaseMessage object null : " + firebaseMessaging);
                                            }
                                        } catch (Exception e) {
                                            log.error("FirebaseNotificationAdapter:processOutBoundMessageF::Exception: " + e.getMessage());
                                        }
                                    } else {
                                        log.error("Service key not found: " + credentials);
                                    }
                                })
                                .subscribe();
                    });
                })
                .subscribe();

        return Mono.just(xMessageListCass);
    }


    public FirebaseMessaging configureFirebaseMessaging(String serviceKey, String appName) throws Exception {
        InputStream serviceAccountStream = new ByteArrayInputStream(serviceKey.getBytes(StandardCharsets.UTF_8));
        GoogleCredentials googleCredentials = GoogleCredentials
                .fromStream(serviceAccountStream);
        FirebaseOptions firebaseOptions = FirebaseOptions
                .builder()
                .setCredentials(googleCredentials)
                .build();
        FirebaseApp existingApp = FirebaseApp.getApps().stream()
                .filter(app -> app.getName().equals(appName))
                .findFirst()
                .orElseGet(() -> {
                    return FirebaseApp.initializeApp(firebaseOptions, appName);
                });
        return FirebaseMessaging.getInstance(existingApp);
    }
}
