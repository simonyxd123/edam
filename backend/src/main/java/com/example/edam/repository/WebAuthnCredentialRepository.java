package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.security.webauthn.WebAuthnCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface WebAuthnCredentialRepository extends BaseMapper<WebAuthnCredential> {

    @Select("SELECT * FROM webauthn_credential WHERE credential_id = #{credentialId} AND revoked = 0 LIMIT 1")
    Optional<WebAuthnCredential> findByCredentialId(String credentialId);

    @Select("SELECT * FROM webauthn_credential WHERE user_id = #{userId} AND revoked = 0 ORDER BY last_used_at DESC")
    List<WebAuthnCredential> findActiveByUserId(Long userId);

    @Update("UPDATE webauthn_credential SET counter = #{counter}, last_used_at = #{now} WHERE id = #{id}")
    int updateCounter(Long id, long counter, LocalDateTime now);

    @Update("UPDATE webauthn_credential SET revoked = 1, revoked_at = #{now}, revoked_reason = #{reason} WHERE id = #{id}")
    int revoke(Long id, LocalDateTime now, String reason);

    @Update("UPDATE webauthn_credential SET revoked = 1, revoked_at = #{now}, revoked_reason = #{reason} WHERE user_id = #{userId}")
    int revokeAllByUserId(Long userId, LocalDateTime now, String reason);
}