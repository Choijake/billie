package com.nextdoor.nextdoor.command;

import com.nextdoor.nextdoor.common.Adapter;
import com.nextdoor.nextdoor.domain.aianalysis.port.AiClientPort;
import com.nextdoor.nextdoor.domain.aianalysis.service.dto.RentalDto;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.MimeType;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Adapter
public class GeminiClientAdapter implements AiClientPort {

    private final String damageAnalyzerPrompt;
    private final String summarizerPrompt;
    private final String pairDamageComparatorPrompt;
    private final ChatClient chatClient;

    public GeminiClientAdapter(
            @Qualifier("geminiProChatClient")
            ChatClient chatClient,
            @Qualifier("damageAnalyzerPrompt")
            String damageAnalyzerPrompt,
            @Qualifier("summarizerPrompt")
            String summarizerPrompt,
            @Qualifier("pairDamageComparatorPrompt")
            String pairDamageComparatorPrompt
    ) {
        this.damageAnalyzerPrompt = damageAnalyzerPrompt;
        this.summarizerPrompt = summarizerPrompt;
        this.pairDamageComparatorPrompt = pairDamageComparatorPrompt;
        this.chatClient = chatClient;
    }

    @Override
    public String analyzeDamage(List<RentalDto.AiImageDto> aiImages) {
        return chatClient.prompt()
                .user(u -> u
                        .text(damageAnalyzerPrompt)
                        .media(aiImages.stream()
                        .map(this::convertToMedia)
                        .toArray(Media[]::new)))
                .call()
                .content();
    }

    @Override
    @Async("asyncExecutor")
    public CompletableFuture<String> compare(RentalDto.AiImageDto[] aiImagePair) {
        List<Message> messages = List.of(
                UserMessage.builder()
                        .text(pairDamageComparatorPrompt)
                        .build(),
                UserMessage.builder()
                        .text("This is a before image.")
                        .media(convertToMedia(aiImagePair[0]))
                        .build(),
                UserMessage.builder()
                        .text("This is an after image.")
                        .media(convertToMedia(aiImagePair[1]))
                        .build());
        return CompletableFuture.completedFuture(
                chatClient.prompt()
                        .messages(messages)
                        .call()
                        .content());
    }

    private Media convertToMedia(RentalDto.AiImageDto aiImage) {
        return Media.builder()
                .mimeType(MimeType.valueOf(aiImage.getMimeType()))
                .data(URI.create(aiImage.getImageUrl()))
                .build();
    }

    @Override
    public String summarize(String content) {
        return chatClient.prompt()
                .user(summarizerPrompt + "\n" + content)
                .call()
                .content();
    }
}
