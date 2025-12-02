package com.nextdoor.nextdoor.command;

import com.nextdoor.nextdoor.common.Adapter;
import com.nextdoor.nextdoor.domain.aianalysis.port.AiAnalysisMatcherCommandPort;
import com.nextdoor.nextdoor.domain.aianalysis.service.dto.ImageMatcherRequestDto;
import com.nextdoor.nextdoor.domain.aianalysis.service.dto.ImageMatcherResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Primary
@Adapter
public class DummyAiAnalysisMatcherCommandAdapter implements AiAnalysisMatcherCommandPort {

    @Override
    public ImageMatcherResponseDto match(ImageMatcherRequestDto request) {
        List<ImageMatcherResponseDto.Match> matches = new ArrayList<>();
        int minSize = Math.min(request.getBefore().size(), request.getAfter().size());
        for (int i = 0; i < minSize; i++) {
            matches.add(ImageMatcherResponseDto.Match.builder()
                    .afterIndex(i)
                    .beforeIndex(i)
                    .similarity(1.0)
                    .build());
        }
        return ImageMatcherResponseDto.builder()
                .afterCount(request.getAfter().size())
                .beforeCount(request.getBefore().size())
                .matches(matches)
                .build();
    }
}
