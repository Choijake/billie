package com.nextdoor.nextdoor.domain.search.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.LocalDateTime;

@Setting(settingPath = "/elasticsearch-settings.json")
@Document(indexName = "posts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostDocument {

    @Id
    private Long id;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "nori_index", searchAnalyzer = "nori_search"),
            otherFields = {
                    @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256),
                    @InnerField(suffix = "raw", type = FieldType.Text, analyzer = "standard"),
                    @InnerField(suffix = "autocomplete", type = FieldType.Text,
                            analyzer = "autocomplete_index", searchAnalyzer = "autocomplete_search")
            }
    )
    private String title;

    @Field(type = FieldType.Text, analyzer = "nori_index", searchAnalyzer = "nori_search")
    private String content;

    @Field(type = FieldType.Keyword)
    private String address;

    @GeoPointField
    private GeoPoint location;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Integer)
    private Integer rentalFee;

    @Field(type = FieldType.Integer)
    private Integer deposit;

    @Field(type = FieldType.Integer)
    private Integer likeCount;

    @Field(type = FieldType.Date)
    private LocalDateTime createdAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoPoint {
        private Double lat;
        private Double lon;
    }
}
