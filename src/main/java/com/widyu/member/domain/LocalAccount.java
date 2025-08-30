package com.widyu.member.domain;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uk_local_member", columnNames = "member_id"),
        @UniqueConstraint(name = "uk_local_email", columnNames = "email")
}
)
public class LocalAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    private String email;

    private String password;

    @Column(name = "is_first")
    private boolean isFirst;

    @Builder(access = AccessLevel.PRIVATE)
    private LocalAccount(final Member member, final String email, final String password) {
        this.member = member;
        this.email = email;
        this.password = password;
        this.isFirst = true;
    }

    public static LocalAccount createLocalAccount(final Member member, final String email, final String password) {
        return LocalAccount.builder()
                .member(member)
                .email(email)
                .password(password)
                .build();
    }

    public void changePassword(final String newPassword) {
        if (this.password.equals(newPassword)) {
            throw new BusinessException(ErrorCode.SAME_PASSWORD);
        }
        this.password = newPassword;
    }
}
