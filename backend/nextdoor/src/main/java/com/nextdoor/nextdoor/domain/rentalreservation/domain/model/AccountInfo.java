package com.nextdoor.nextdoor.domain.rentalreservation.domain.model;

import com.nextdoor.nextdoor.domain.rentalreservation.domain.util.ValidationUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode
public class AccountInfo {

    @Column(name = "account_no", nullable = true, length = 30)
    private String accountNo;

    @Column(name = "bank_code", nullable = true, length = 10)
    private String bankCode;

    public AccountInfo(String accountNo, String bankCode) {
        ValidationUtils.validateNotBlank(accountNo, "계좌번호");
        ValidationUtils.validateNotBlank(bankCode, "은행코드");
        this.accountNo = accountNo;
        this.bankCode = bankCode;
    }
}
