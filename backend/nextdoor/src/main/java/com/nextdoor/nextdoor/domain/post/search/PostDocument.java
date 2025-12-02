package com.nextdoor.nextdoor.domain.post.search;

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
          mainField = @Field(type = FieldType.Text, analyzer = "nori"),
          otherFields = {@InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256)}
  )
  private String title;

  @MultiField(
          mainField = @Field(type = FieldType.Text, analyzer = "nori"),
          otherFields = {@InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256)}
  )
  private String content;

  @Field(type = FieldType.Long)
  private Long rentalFee;

  @Field(type = FieldType.Long)
  private Long deposit;

  @MultiField(
          mainField = @Field(type = FieldType.Text, analyzer = "nori"),
          otherFields = {@InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256)}
  )
  private String address;

  @GeoPointField
  private GeoPoint location;

  @Field(type = FieldType.Keyword)
  private String category;

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