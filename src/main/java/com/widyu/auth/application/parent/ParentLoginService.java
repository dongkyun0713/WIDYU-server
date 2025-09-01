package com.widyu.auth.application.parent;

import com.widyu.auth.dto.request.ParentSignInRequest;
import com.widyu.auth.dto.request.ParentSignUpRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberType;
import com.widyu.member.domain.ParentProfile;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.ParentProfileRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ParentLoginService {

    private final MemberRepository memberRepository;
    private final ParentProfileRepository parentProfileRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public int parentSignUpBulk(List<ParentSignUpRequest> requests) {
        validateRequestsNotEmpty(requests);
        validateDuplicateCodesInRequest(requests);
        validateCodesNotExistInDb(extractInviteCodes(requests));

        List<Member> members = buildMembersFromRequests(requests);
        saveAllMembers(members);

        List<ParentProfile> profiles = buildProfilesFromRequests(requests, members);
        saveAllProfiles(profiles);

        return members.size();
    }

    @Transactional
    public TokenPairResponse parentSignIn(ParentSignInRequest request) {
        ParentProfile parentProfile = findByInviteCodeOrThrow(request.inviteCode());
        return generateTokenPairForMember(parentProfile.getMember());
    }

    private void validateRequestsNotEmpty(List<ParentSignUpRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, ": 요청 리스트가 비어 있습니다.");
        }
    }

    private void validateDuplicateCodesInRequest(List<ParentSignUpRequest> requests) {
        Map<String, Long> counts = requests.stream()
                .map(ParentSignUpRequest::inviteCode)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<String> dupInRequest = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();

        if (!dupInRequest.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVITE_CODE_DUPLICATED,
                    " : 요청 내 중복 초대코드=" + dupInRequest
            );
        }
    }

    private void validateCodesNotExistInDb(Set<String> inviteCodes) {
        List<ParentProfile> existing = parentProfileRepository.findAllByInviteCodeIn(inviteCodes);
        if (!existing.isEmpty()) {
            List<String> dupInDb = existing.stream()
                    .map(ParentProfile::getInviteCode)
                    .distinct()
                    .toList();
            throw new BusinessException(
                    ErrorCode.INVITE_CODE_DUPLICATED,
                    " : 기존에 존재하는 초대코드=" + dupInDb
            );
        }
    }

    private Set<String> extractInviteCodes(List<ParentSignUpRequest> requests) {
        return requests.stream()
                .map(ParentSignUpRequest::inviteCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Member> buildMembersFromRequests(List<ParentSignUpRequest> requests) {
        return requests.stream()
                .map(req -> Member.createMember(
                        MemberType.PARENT,
                        req.name(),
                        req.phoneNumber()
                ))
                .toList();
    }

    private void saveAllMembers(List<Member> members) {
        memberRepository.saveAll(members);
    }

    private List<ParentProfile> buildProfilesFromRequests(List<ParentSignUpRequest> requests, List<Member> members) {
        List<ParentProfile> profiles = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            ParentSignUpRequest req = requests.get(i);
            Member member = members.get(i);
            ParentProfile profile = ParentProfile.createParentProfile(
                    member,
                    req.birthDate(),
                    req.address(),
                    req.detailAddress(),
                    req.inviteCode()
            );
            profiles.add(profile);
        }
        return profiles;
    }

    private void saveAllProfiles(List<ParentProfile> profiles) {
        parentProfileRepository.saveAll(profiles);
    }

    private ParentProfile findByInviteCodeOrThrow(String inviteCode) {
        return parentProfileRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_CODE_NOT_FOUND, inviteCode));
    }

    private TokenPairResponse generateTokenPairForMember(Member member) {
        return jwtTokenProvider.generateTokenPair(member.getId(), member.getRole());
    }
}
