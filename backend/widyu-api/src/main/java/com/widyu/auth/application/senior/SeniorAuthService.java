package com.widyu.auth.application.senior;

import com.widyu.auth.dto.request.SeniorSignInRequest;
import com.widyu.auth.dto.request.SeniorSignUpRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.addressbookmark.application.GeocodingService;
import com.widyu.goal.addressbookmark.dto.response.GeocodingResponse;
import com.widyu.location.parentlocation.repository.ParentLocationRepository;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.parentlocation.LocationType;
import com.widyu.parentlocation.ParentLocation;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeniorAuthService {

    private static final String FAMILY_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int FAMILY_CODE_LENGTH = 6;
    private static final int FAMILY_CODE_MAX_ATTEMPTS = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MemberRepository memberRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final FamilyRepository familyRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final ParentLocationRepository parentLocationRepository;
    private final GeocodingService geocodingService;
    private final JwtTokenProvider jwtTokenProvider;
    private final MemberUtil memberUtil;

    @Transactional
    public void seniorSignUpBulk(List<SeniorSignUpRequest> requests) {
        Member guardian = memberUtil.getCurrentMember();
        validateIsGuardian(guardian);

        validateRequestsNotEmpty(requests);
        validatePhoneNumbersAvailable(requests);

        if (familyMembershipRepository.findByGuardianId(guardian.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.ALREADY_CONNECTED_TO_FAMILY, "이미 가족에 소속되어 있습니다.");
        }

        Family family = createAndSaveFamily();

        List<Member> members = buildMembersFromRequests(requests);
        saveAllMembers(members);

        List<SeniorProfile> profiles = buildProfilesFromRequests(requests, members, family);
        saveAllProfiles(profiles);

        FamilyMembership leaderMembership = FamilyMembership.createLeaderMembership(family, guardian);
        familyMembershipRepository.save(leaderMembership);

        saveHomeParentLocations(requests, members);
    }

    private void saveHomeParentLocations(List<SeniorSignUpRequest> requests, List<Member> members) {
        for (int i = 0; i < requests.size(); i++) {
            String address = requests.get(i).address();
            Member member = members.get(i);
            GeocodingResponse geo = geocodingService.geocode(address);
            parentLocationRepository.save(ParentLocation.builder()
                    .member(member)
                    .locationType(LocationType.HOME)
                    .placeAddress(address)
                    .latitude(geo.latitude())
                    .longitude(geo.longitude())
                    .name("집")
                    .status(Status.ACTIVE)
                    .build());
        }
    }

    @Transactional
    public TokenPairResponse seniorSignIn(SeniorSignInRequest request) {
        SeniorProfile seniorProfile = findByInviteCodeAndPhoneNumber(request.inviteCode(), request.phoneNumber());
        return generateTokenPairForMember(seniorProfile.getMember());
    }

    private void validateRequestsNotEmpty(List<SeniorSignUpRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException(ErrorCode.SENIOR_SIGNUP_REQUEST_EMPTY);
        }
    }

    // 전화번호는 회원 전체에서 하나여야 한다. 요청 안 중복과 이미 가입된 번호를 모두 막는다 (LLD-0116)
    // ponytail: 확인과 저장 사이 동시 요청은 막지 못한다. 기존 중복을 정리한 뒤 DB 유일 제약으로 닫는다
    private void validatePhoneNumbersAvailable(List<SeniorSignUpRequest> requests) {
        Set<String> phoneNumbers = new HashSet<>();
        for (SeniorSignUpRequest request : requests) {
            if (!phoneNumbers.add(request.phoneNumber())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "요청 안에 같은 전화번호가 두 번 이상 있습니다.");
            }
            if (memberRepository.existsByPhoneNumber(request.phoneNumber())) {
                throw new BusinessException(ErrorCode.ALREADY_REGISTERED_PHONE_NUMBER);
            }
        }
    }

    private void validateIsGuardian(Member member) {
        if (member.getType() != MemberType.GUARDIAN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private Family createAndSaveFamily() {
        String familyCode = generateUniqueFamilyCode();
        Family family = Family.createFamily(familyCode);
        return familyRepository.save(family);
    }

    private List<Member> buildMembersFromRequests(List<SeniorSignUpRequest> requests) {
        return requests.stream()
                .map(req -> Member.createMember(
                        MemberType.SENIOR,
                        req.name(),
                        req.phoneNumber()
                ))
                .toList();
    }

    private void saveAllMembers(List<Member> members) {
        memberRepository.saveAll(members);
    }

    private List<SeniorProfile> buildProfilesFromRequests(List<SeniorSignUpRequest> requests,
                                                           List<Member> members, Family family) {
        List<SeniorProfile> profiles = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            SeniorSignUpRequest req = requests.get(i);
            Member member = members.get(i);
            SeniorProfile profile = SeniorProfile.createSeniorProfile(
                    member,
                    family,
                    req.address(),
                    req.inviteCode(),
                    req.birthDate()
            );
            profiles.add(profile);
        }
        return profiles;
    }

    private String generateUniqueFamilyCode() {
        for (int attempt = 0; attempt < FAMILY_CODE_MAX_ATTEMPTS; attempt++) {
            String code = generateCode();
            if (!familyRepository.existsByFamilyCode(code)) {
                return code;
            }
        }
        throw new BusinessException(ErrorCode.FAMILY_CODE_GENERATION_FAILED);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(FAMILY_CODE_LENGTH);
        for (int i = 0; i < FAMILY_CODE_LENGTH; i++) {
            sb.append(FAMILY_CODE_CHARS.charAt(SECURE_RANDOM.nextInt(FAMILY_CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private void saveAllProfiles(List<SeniorProfile> profiles) {
        seniorProfileRepository.saveAll(profiles);
    }

    private SeniorProfile findByInviteCodeAndPhoneNumber(String inviteCode, String phoneNumber) {
        List<SeniorProfile> profiles = seniorProfileRepository.findAllByInviteCodeAndMemberPhoneNumber(inviteCode, phoneNumber);
        if (profiles.isEmpty()) {
            log.warn("초대코드로 시니어 프로필을 찾을 수 없습니다.");
            throw new BusinessException(ErrorCode.INVITE_CODE_NOT_FOUND);
        }
        // 같은 초대코드와 번호의 시니어가 여럿이면 누구로 로그인시킬지 알 수 없어 임의로 고르지 않는다
        if (profiles.size() > 1) {
            log.warn("같은 초대코드와 전화번호의 시니어가 여러 명이라 로그인 거부");
            throw new BusinessException(ErrorCode.PHONE_NUMBER_SHARED_BY_MEMBERS);
        }
        return profiles.get(0);
    }

    private TokenPairResponse generateTokenPairForMember(Member member) {
        return jwtTokenProvider.generateTokenPair(member.getId(), member.getRole(), "senior");
    }
}
