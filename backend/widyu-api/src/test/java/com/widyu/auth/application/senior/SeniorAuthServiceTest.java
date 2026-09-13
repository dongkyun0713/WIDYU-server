package com.widyu.auth.application.senior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.widyu.auth.dto.request.SeniorSignInRequest;
import com.widyu.auth.dto.request.SeniorSignUpRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
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
import com.widyu.parentlocation.ParentLocation;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("SeniorAuthService 단위 테스트")
class SeniorAuthServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private FamilyRepository familyRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private ParentLocationRepository parentLocationRepository;
    @Mock private GeocodingService geocodingService;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private MemberUtil memberUtil;

    @InjectMocks
    private SeniorAuthService seniorAuthService;

    @Test
    @DisplayName("시니어 일괄 등록 시 방장을 대표 연락처로 저장한다")
    void 시니어_일괄_등록_시_방장을_대표연락처로_저장한다() {
        // given
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        ReflectionTestUtils.setField(guardian, "id", 1L);

        List<SeniorSignUpRequest> requests = List.of(
                new SeniorSignUpRequest("부모님", LocalDate.of(1950, 1, 1), "01011112222", "서울시 강남구", "1234567"),
                new SeniorSignUpRequest("할머니", LocalDate.of(1948, 2, 2), "01033334444", "서울시 서초구", "7654321")
        );

        Member seniorMember1 = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");
        Member seniorMember2 = Member.createMember(MemberType.SENIOR, "할머니", "01033334444");

        Family family = Family.createFamily("ABC123");
        ReflectionTestUtils.setField(family, "id", 1L);

        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(familyRepository.existsByFamilyCode(anyString())).willReturn(false);
        given(familyRepository.save(any(Family.class))).willReturn(family);
        given(memberRepository.saveAll(anyList())).willReturn(List.of(seniorMember1, seniorMember2));
        given(seniorProfileRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
        given(familyMembershipRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(geocodingService.geocode(anyString())).willReturn(new GeocodingResponse(37.55, 126.85));

        // when
        seniorAuthService.seniorSignUpBulk(requests);

        // then
        verify(memberRepository).saveAll(anyList());
        verify(seniorProfileRepository).saveAll(anyList());
        ArgumentCaptor<FamilyMembership> membershipCaptor = ArgumentCaptor.forClass(FamilyMembership.class);
        verify(familyMembershipRepository).save(membershipCaptor.capture());

        FamilyMembership membership = membershipCaptor.getValue();
        assertThat(membership.isLeader()).isTrue();
        assertThat(membership.isRepresentative()).isTrue();
    }

    @Test
    @DisplayName("시니어 일괄 등록 시 각 시니어의 HOME 안심구역이 생성된다")
    void 시니어_일괄_등록_시_HOME_안심구역이_생성된다() {
        // given
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        ReflectionTestUtils.setField(guardian, "id", 1L);

        Member seniorMember1 = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");
        Member seniorMember2 = Member.createMember(MemberType.SENIOR, "할머니", "01033334444");
        Family family = Family.createFamily("ABC123");
        ReflectionTestUtils.setField(family, "id", 1L);

        List<SeniorSignUpRequest> requests = List.of(
                new SeniorSignUpRequest("부모님", LocalDate.of(1950, 1, 1), "01011112222", "서울시 강남구", "1234567"),
                new SeniorSignUpRequest("할머니", LocalDate.of(1948, 2, 2), "01033334444", "서울시 서초구", "7654321")
        );

        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(familyRepository.existsByFamilyCode(anyString())).willReturn(false);
        given(familyRepository.save(any(Family.class))).willReturn(family);
        given(memberRepository.saveAll(anyList())).willReturn(List.of(seniorMember1, seniorMember2));
        given(seniorProfileRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
        given(familyMembershipRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(geocodingService.geocode(anyString())).willReturn(new GeocodingResponse(37.55, 126.85));

        // when
        seniorAuthService.seniorSignUpBulk(requests);

        // then
        verify(parentLocationRepository, org.mockito.Mockito.times(2)).save(any(ParentLocation.class));
    }

    @Test
    @DisplayName("시니어 일괄 등록 시 지오코딩 실패하면 가입도 실패한다")
    void 시니어_일괄_등록_시_지오코딩_실패하면_가입도_실패한다() {
        // given
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        ReflectionTestUtils.setField(guardian, "id", 1L);

        Member seniorMember = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");
        Family family = Family.createFamily("ABC123");
        ReflectionTestUtils.setField(family, "id", 1L);

        List<SeniorSignUpRequest> requests = List.of(
                new SeniorSignUpRequest("부모님", LocalDate.of(1950, 1, 1), "01011112222", "서울시 강남구", "1234567")
        );

        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(familyRepository.existsByFamilyCode(anyString())).willReturn(false);
        given(familyRepository.save(any(Family.class))).willReturn(family);
        given(memberRepository.saveAll(anyList())).willReturn(List.of(seniorMember));
        given(seniorProfileRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
        given(familyMembershipRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(geocodingService.geocode(anyString()))
                .willThrow(new BusinessException(ErrorCode.BAD_REQUEST, "입력한 주소의 좌표를 찾을 수 없습니다. 정확한 도로명주소를 입력해 주세요."));

        // when & then
        assertThatThrownBy(() -> seniorAuthService.seniorSignUpBulk(requests))
                .isInstanceOf(BusinessException.class);
        verify(parentLocationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("이미 가족에 소속된 보호자가 시니어 등록을 시도하면 BusinessException을 던진다")
    void 이미_가족에_소속된_보호자가_시니어_등록_시_예외가_발생한다() {
        // given
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        ReflectionTestUtils.setField(guardian, "id", 1L);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(familyMembershipRepository.findByGuardianId(1L)).willReturn(Optional.of(mock(FamilyMembership.class)));

        List<SeniorSignUpRequest> requests = List.of(
                new SeniorSignUpRequest("부모님", LocalDate.of(1950, 1, 1), "01011112222", "서울", "1234567")
        );

        // when & then
        assertThatThrownBy(() -> seniorAuthService.seniorSignUpBulk(requests))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_CONNECTED_TO_FAMILY);
    }

    @Test
    @DisplayName("빈 리스트로 시니어 등록 시 BusinessException을 던진다")
    void 빈_리스트로_시니어_등록_시_예외가_발생한다() {
        // given
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        given(memberUtil.getCurrentMember()).willReturn(guardian);

        // when & then
        assertThatThrownBy(() -> seniorAuthService.seniorSignUpBulk(List.of()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENIOR_SIGNUP_REQUEST_EMPTY);
    }

    @Test
    @DisplayName("null 리스트로 시니어 등록 시 BusinessException을 던진다")
    void null_리스트로_시니어_등록_시_예외가_발생한다() {
        // given
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        given(memberUtil.getCurrentMember()).willReturn(guardian);

        // when & then
        assertThatThrownBy(() -> seniorAuthService.seniorSignUpBulk(null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENIOR_SIGNUP_REQUEST_EMPTY);
    }

    @Test
    @DisplayName("유효한 초대코드와 전화번호로 시니어 로그인 시 토큰 쌍을 반환한다")
    void 유효한_초대코드와_전화번호로_로그인_시_토큰쌍을_반환한다() {
        // given
        String inviteCode = "1234567";
        String phone = "01011112222";
        Member seniorMember = Member.createMember(MemberType.SENIOR, "부모님", phone);
        ReflectionTestUtils.setField(seniorMember, "id", 2L);

        Family family = Family.createFamily("ABC123");
        ReflectionTestUtils.setField(family, "id", 1L);

        SeniorProfile seniorProfile = SeniorProfile.createSeniorProfile(
                seniorMember, family, "서울", inviteCode, LocalDate.of(1950, 1, 1)
        );
        TokenPairResponse expectedToken = TokenPairResponse.of(2L, "access", "refresh");

        given(seniorProfileRepository.findByInviteCodeAndMemberPhoneNumber(inviteCode, phone))
                .willReturn(Optional.of(seniorProfile));
        given(jwtTokenProvider.generateTokenPair(any(), any(), any())).willReturn(expectedToken);

        // when
        TokenPairResponse result = seniorAuthService.seniorSignIn(new SeniorSignInRequest(inviteCode, phone));

        // then
        assertThat(result).isEqualTo(expectedToken);
    }

    @Test
    @DisplayName("유효하지 않은 초대코드로 로그인 시 BusinessException을 던진다")
    void 유효하지_않은_초대코드로_로그인_시_예외가_발생한다() {
        // given
        given(seniorProfileRepository.findByInviteCodeAndMemberPhoneNumber("0000000", "01011112222"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> seniorAuthService.seniorSignIn(
                new SeniorSignInRequest("0000000", "01011112222")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVITE_CODE_NOT_FOUND);
    }

    @Test
    @DisplayName("가족 코드 생성 최대 시도 초과 시 BusinessException을 던진다")
    void 가족코드_생성_최대_시도_초과_시_예외가_발생한다() {
        // given
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(familyRepository.existsByFamilyCode(anyString())).willReturn(true);

        List<SeniorSignUpRequest> requests = List.of(
                new SeniorSignUpRequest("부모님", LocalDate.of(1950, 1, 1), "01011112222", "서울", "1234567")
        );

        // when & then
        assertThatThrownBy(() -> seniorAuthService.seniorSignUpBulk(requests))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FAMILY_CODE_GENERATION_FAILED);
    }

    @Test
    @DisplayName("시니어 일괄 등록 시 요청 개수만큼 멤버가 생성된다")
    void 시니어_일괄_등록_시_요청_개수만큼_멤버가_생성된다() {
        // given
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        Family family = Family.createFamily("XYZ789");
        ReflectionTestUtils.setField(family, "id", 1L);

        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(familyRepository.existsByFamilyCode(anyString())).willReturn(false);
        given(familyRepository.save(any(Family.class))).willReturn(family);

        List<SeniorSignUpRequest> requests = List.of(
                new SeniorSignUpRequest("부모님", LocalDate.of(1950, 1, 1), "01011112222", "서울", "1234567"),
                new SeniorSignUpRequest("할머니", LocalDate.of(1948, 2, 2), "01033334444", "부산", "7654321")
        );

        given(memberRepository.saveAll(anyList())).willAnswer(inv -> {
            List<Member> members = inv.getArgument(0);
            assertThat(members).hasSize(2);
            return members;
        });
        given(seniorProfileRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
        given(familyMembershipRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(geocodingService.geocode(anyString())).willReturn(new GeocodingResponse(37.55, 126.85));

        // when
        seniorAuthService.seniorSignUpBulk(requests);

        // then
        verify(memberRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("시니어 타입 회원이 시니어 등록을 시도하면 FORBIDDEN 예외가 발생한다")
    void 시니어_타입_회원이_시니어_등록을_시도하면_FORBIDDEN_예외가_발생한다() {
        // given — SENIOR 타입 Member
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        given(memberUtil.getCurrentMember()).willReturn(senior);

        List<SeniorSignUpRequest> requests = List.of(
                new SeniorSignUpRequest("부모님", LocalDate.of(1950, 1, 1), "01011112222", "서울", "1234567")
        );

        // when & then
        assertThatThrownBy(() -> seniorAuthService.seniorSignUpBulk(requests))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }
}
