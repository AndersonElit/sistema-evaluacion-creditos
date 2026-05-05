package com.mscreditevaluation.sqsproducer.adapter;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

@ApplicationScoped
public class SqsMessagePublisher {

    @Inject
    SqsAsyncClient sqsClient;

    @ConfigProperty(name = "sqs.queue.url")
    String queueUrl;

    public Uni<SendMessageResponse> publish(String messageBody) {
        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .build();
        return Uni.createFrom().completionStage(() -> sqsClient.sendMessage(request));
    }
}
