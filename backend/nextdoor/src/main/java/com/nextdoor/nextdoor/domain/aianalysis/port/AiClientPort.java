package com.nextdoor.nextdoor.domain.aianalysis.port;

import com.nextdoor.nextdoor.domain.aianalysis.service.dto.RentalDto;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AiClientPort {

    String analyzeDamage(List<RentalDto.AiImageDto> aiImages);

    CompletableFuture<String> compare(RentalDto.AiImageDto[] aiImagePair);

    String summarize(String content);
}
