package com.example.refile.service;

import com.example.refile.model.Attachment;
import com.example.refile.model.User;
import com.example.refile.util.HttpUtil;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static com.example.refile.util.Constants.APPLICATION_NAME;

@Service
@RequiredArgsConstructor
public class GmailService {

    // default user id for authenticated users querying gmail
    private static final String USER_ID = "me";

    private final CategorizationService categorizationService;
    private final CredentialService credentialService;
    private final AttachmentService attachmentService;

    @Qualifier("ioExecutor")
    private final ExecutorService ioExecutor;

    @Qualifier("cpuExecutor")
    private final ExecutorService cpuExecutor;

    /**
     * Retrieves attachments for a given user from the database. If attachments are empty, a sync will be be made to
     * fetch attachments from Gmail.
     *
     * @param user User
     * @return List of Attachments belonging to the user
     */
    public List<Attachment> getAttachments(User user) throws IOException {
        List<Attachment> attachments = user.getAttachments();

        if (attachments.isEmpty()) {
            return syncAttachments(user);
        }
        return attachments;
    }

    public List<Attachment> syncAttachments(User user) throws IOException {
        attachmentService.deleteAllAttachments(user.getAttachments());
        user.getAttachments().clear();

        Gmail gmail = getGmailClient(user.getUserId());

        List<Message> messageIds = getMessageIdsWithAttachments(gmail);
        List<Attachment> attachments = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Message message : messageIds) {
            futures.add(CompletableFuture.supplyAsync(() -> getFullMessage(message, gmail), ioExecutor)
                                         .thenApplyAsync(fullMessage -> processMessage(fullMessage, user), cpuExecutor)
                                         .thenAccept(attachments::addAll));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        attachmentService.saveAllAttachments(attachments);
        attachments.sort(Comparator.comparing(Attachment::getCreatedDate, Comparator.reverseOrder()));
        return attachments;
    }

    private List<Attachment> processMessage(Message message, User user) {
        List<Attachment> attachments = new ArrayList<>();

        // header data
        String[] headers = extractMessageMetadata(message);
        String sender = headers[0];
        String subject = headers[1];
        List<String> subjectCategoryExtraction = categorizationService.extractCategories(subject, user.getCategories());

        // email body data
        List<MessagePart> parts = message.getPayload().getParts();
        String body = extractEmailBody(parts.get(0));
        List<String> bodyCategoryExtraction = new ArrayList<>();
        if (!body.isEmpty()) {
            bodyCategoryExtraction.addAll(categorizationService.extractCategories(body, user.getCategories()));
        }

        // attachment data
        for (int i = 1; i < parts.size(); i++) {
            MessagePart attachmentPart = parts.get(i);
            String fileName = attachmentPart.getFilename();
            String extension = fileName.substring(fileName.indexOf(".") + 1);
            String attachmentId = attachmentPart.getBody().getAttachmentId();

            Attachment attachment = Attachment.builder()
                                              .user(user)
                                              .createdDate(Date.from(Instant.ofEpochMilli(message.getInternalDate())))
                                              .name(fileName)
                                              .sender(sender)
                                              .subject(subject)
                                              .extension(extension)
                                              .gId(attachmentId)
                                              .labelIds(new HashSet<>(message.getLabelIds()))
                                              .categories(new HashSet<>())
                                              .build();

            attachment.getCategories().addAll(subjectCategoryExtraction);
            attachment.getCategories().addAll(bodyCategoryExtraction);

//            MessagePartBody attachmentPartBody = getAttachmentPartBody(message.getId(), attachmentId, gmail);

            attachments.add(attachment);
        }

        return attachments;
    }

    private String[] extractMessageMetadata(Message message) {
        String[] headers = new String[2];

        message.getPayload().getHeaders().forEach(header -> {
            String name = header.getName();
            if ("From".equals(name)) {
                headers[0] = header.getValue();
            } else if ("Subject".equals(name)) {
                headers[1] = header.getValue();
            }
        });

        return headers;
    }

    private String extractEmailBody(MessagePart messagePart) {
        MessagePart currPart = messagePart;
        if (currPart.getParts() != null) {
            while (currPart.getBody().getData() == null) {
                currPart = currPart.getParts().get(0);
            }
        }

        if (currPart.getBody().getData() != null) {
            return new String(Base64.decodeBase64(currPart.getBody().getData()),
                    StandardCharsets.UTF_8);
        }
        return "";
    }

    private MessagePartBody getAttachmentPartBody(String messageId, String attachmentId, Gmail gmail) throws IOException {
        return gmail.users()
                    .messages()
                    .attachments()
                    .get(USER_ID, messageId, attachmentId).execute();
    }

    private List<Message> getMessageIdsWithAttachments(Gmail gmail) throws IOException {
        Gmail.Users.Messages.List request = gmail.users().messages().list(USER_ID).setQ("has:attachment");

        ListMessagesResponse response = request.execute();
        List<Message> messageIdList = new ArrayList<>(response.getMessages());

        while (response.getNextPageToken() != null) {
            String nextPageToken = response.getNextPageToken();
            request = gmail.users().messages().list(USER_ID)
                           .setPageToken(nextPageToken)
                           .setQ("has:attachment");

            response = request.execute();
            messageIdList.addAll(response.getMessages());
        }

        return messageIdList;
    }

    private Message getFullMessage(Message message, Gmail gmail) {
        int count = 0;
        int maxTries = 3;
        while (true) {
            try {
                return gmail.users().messages().get(USER_ID, message.getId()).execute();
            } catch (Exception e) {
                if (++count == maxTries) throw new RuntimeException(e.getMessage());
            }
        }
    }

    /**
     * Returns a new Gmail Client instance
     *
     * @param userId User ID of the user
     * @return Gmail
     */
    private Gmail getGmailClient(Long userId) {
        Credential credential = credentialService.getCredential(userId).orElseThrow();
        return new Gmail.Builder(HttpUtil.getHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
