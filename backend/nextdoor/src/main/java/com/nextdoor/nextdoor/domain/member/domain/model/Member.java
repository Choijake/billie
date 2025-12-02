package com.nextdoor.nextdoor.domain.member.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Getter
@Entity
public class Member extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    /** 웹소켓 고유 식별자 **/
    @UuidGenerator
    private String uuid;

    /** 핀테크 외부 시스템(user_key) 식별자 **/
    @Column(name = "user_key", length = 36)
    private String userKey;

    @NotNull
    private String providerId;

    private String birth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private String address;

    private String profileImageUrl;

    @NotNull
    private String nickname;

    private String authProvider;

    /** 안심비밀번호 6자리 **/
    @Column(name = "secure_password", length = 6)
    private String securePassword;

    public void updateBirth(String birth) {
        if (birth != null) {
            this.birth = birth;
        }
    }

    public void updateGender(Gender gender) {
        if (gender != null) {
            this.gender = gender;
        }
    }

    public void updateAddress(String address) {
        if (address != null) {
            this.address = address;
        }
    }

    public void updateUserKey(String userKey) {
        this.userKey = userKey;
    }
}
