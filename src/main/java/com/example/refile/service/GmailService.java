package com.example.refile.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

@Service
public class GmailService {

    private static final String APPLICATION_NAME = "ReFile";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private NetHttpTransport httpTransport = null;

    public GmailService() {
        try {
            this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
        }
    }

    public void getAttachments(Credential credential) throws IOException {

        // need to create a new Gmail instance for every invocation of this method call
        Gmail service = new Gmail.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();

        Gmail.Users.Messages.List request = service.users().messages().list("me").setQ("has:attachment");

        ListMessagesResponse response = request.execute();
        List<Message> messageIdList = new ArrayList<>(response.getMessages());

        while (response.getNextPageToken() != null) {
            String nextPageToken = response.getNextPageToken();
            request = service.users().messages().list("me")
                             .setPageToken(nextPageToken)
                             .setQ("has:attachment");

            response = request.execute();
            messageIdList.addAll(response.getMessages());
        }

        List<Message> messageList = new ArrayList<>();
        for (Message messageId : messageIdList) {
            Message message = service.users().messages().get("me", messageId.getId()).execute();
            messageList.add(message);
        }

        for (Message message : messageList) {
            System.out.println(message.getPayload());
        }
    }
}
