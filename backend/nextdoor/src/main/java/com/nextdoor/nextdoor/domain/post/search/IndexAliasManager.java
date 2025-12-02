package com.nextdoor.nextdoor.domain.post.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping; // 매핑 타입
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class IndexAliasManager {

    private static final String ALIAS = "posts";
    private static final DateTimeFormatter INDEX_SUFFIX_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private final ElasticsearchClient es;

    public String prepareNewIndex() throws IOException {
        final String newIndex = ALIAS + "_" + INDEX_SUFFIX_FMT.format(Instant.now());

        TypeMapping mapping = resolveAliasIndex(ALIAS)
                .map(oldIndex -> {
                    try {
                        GetMappingResponse gm = es.indices().getMapping(g -> g.index(oldIndex));
                        var state = gm.result().get(oldIndex);
                        return state != null ? state.mappings() : null;
                    } catch (IOException e) {
                        throw new RuntimeException("인덱스에서 매핑 불러오기가 실패했습니다: " + oldIndex, e);
                    }
                })
                .orElse(null);

        CreateIndexRequest.Builder create = new CreateIndexRequest.Builder().index(newIndex);
        if (mapping != null) {
            create.mappings(mapping);
        }

        create.settings(s -> s
                .numberOfReplicas("0")
                .refreshInterval(Time.of(t -> t.time("-1")))
        );

        es.indices().create(create.build());
        log.info("전체 인덱싱을 위한 새로운 인덱스가 생성되었습니다: {}", newIndex);
        return newIndex;
    }

    public void finalizeAndSwap(String newIndex, String finalReplica, String finalRefresh) throws IOException {
        es.indices().refresh(r -> r.index(newIndex));

        es.indices().putSettings(ps -> ps
                .index(newIndex)
                .settings(s -> s
                        .numberOfReplicas(finalReplica)
                        .refreshInterval(Time.of(t -> t.time(finalRefresh)))
                )
        );

        Optional<String> oldIndexOpt = resolveAliasIndex(ALIAS);

        es.indices().updateAliases(u -> {
            oldIndexOpt.ifPresent(old ->
                    u.actions(a -> a.remove(r -> r.index(old).alias(ALIAS)))
            );
            u.actions(a -> a.add(ad -> ad
                    .index(newIndex)
                    .alias(ALIAS)
                    .isWriteIndex(true)
            ));
            return u;
        });
    }

    public Optional<String> resolveAliasIndex(String alias) {
        try {
            GetAliasResponse res = es.indices().getAlias(g -> g.name(alias));

            return res.result().entrySet().stream()
                    .sorted((e1, e2) -> {
                        boolean w1 = isWriteIndex(e1.getValue(), alias);
                        boolean w2 = isWriteIndex(e2.getValue(), alias);
                        return Boolean.compare(!w1, !w2);
                    })
                    .map(Map.Entry::getKey)
                    .findFirst();

        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException notFound) {
            return Optional.empty();
        } catch (IOException io) {
            throw new RuntimeException("alias 불러오기가 실패 했습니다: " + alias, io);
        }
    }

    private static boolean isWriteIndex(IndexAliases aliases, String alias) {
        if (aliases == null || aliases.aliases() == null) return false;
        var meta = aliases.aliases().get(alias);
        return meta != null && Boolean.TRUE.equals(meta.isWriteIndex());
    }
}
