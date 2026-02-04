package com.nextdoor.nextdoor.domain.post.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum Category {

    DIGITAL_DEVICE        ("디지털기기"),
    HOME_APPLIANCE        ("생활가전"),
    FURNITURE_INTERIOR    ("가구/인테리어"),
    LIVING_KITCHEN        ("생활/주방"),
    BABY_CHILDREN         ("유아동"),
    BABY_BOOK             ("유아도서"),
    WOMEN_CLOTHING        ("여성의류"),
    WOMEN_ACCESSORIES     ("여성잡화"),
    MEN_FASHION_ACCESSORIES("남성패션/잡화"),
    BEAUTY                ("뷰티/미용"),
    SPORTS_LEISURE        ("스포츠/레저"),
    HOBBY_GAMES_MUSIC     ("취미/게임/음반"),
    BOOK                  ("도서"),
    TICKET_VOUCHER        ("티켓/교환권"),
    PROCESSED_FOOD        ("가공식품"),
    HEALTH_SUPPLEMENT     ("건강기능식품"),
    PET_SUPPLIES          ("반려동물용품"),
    PLANT                 ("식물"),
    ETC_USED_GOODS        ("기타 중고물품");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    @Override
    public String toString() {
        return displayName;
    }

    @JsonCreator
    public static Category from(String value) {
        for (Category ct : Category.values()) {
            if (ct.displayName.equals(value)) {
                return ct;
            }
            if (ct.name().equalsIgnoreCase(value)) {
                return ct;
            }
        }
        throw new IllegalArgumentException(value + "는 존재하지 않는 카테고리입니다");
    }
}