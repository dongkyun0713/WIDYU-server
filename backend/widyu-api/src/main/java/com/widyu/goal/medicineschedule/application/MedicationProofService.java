package com.widyu.goal.medicineschedule.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.medicineschedule.dto.response.MedicationProofResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationProofService {

    private static final String PROOF_IMAGE_DIRECTORY = "medication-proof";

    private final MedicationProofTransactionService transactionService;
    private final MemberUtil memberUtil;
    private final S3Service s3Service;

    public MedicationProofResponse verifyMedication(Long scheduleId, List<MultipartFile> images) {
        Long memberId = memberUtil.getCurrentMember().getId();
        transactionService.validateBeforeUpload(memberId, scheduleId);
        List<String> imageUrls = uploadProofImages(images, memberId);
        try {
            return transactionService.verifyMedication(memberId, scheduleId, imageUrls);
        } catch (RuntimeException exception) {
            deleteUploadedImages(imageUrls);
            throw exception;
        }
    }

    private List<String> uploadProofImages(List<MultipartFile> images, Long memberId) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }

        List<String> uploadedUrls = new ArrayList<>();
        try {
            for (MultipartFile image : images) {
                String directory = PROOF_IMAGE_DIRECTORY + "/" + memberId;
                String filePath = s3Service.generateFilePath(directory, image.getOriginalFilename());
                uploadedUrls.add(s3Service.uploadFile(image, filePath));
            }
            return uploadedUrls;
        } catch (Exception exception) {
            deleteUploadedImages(uploadedUrls);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "복용 인증 이미지 업로드에 실패했습니다.");
        }
    }

    private void deleteUploadedImages(List<String> imageUrls) {
        for (String imageUrl : imageUrls) {
            try {
                s3Service.deleteFile(imageUrl);
            } catch (Exception exception) {
                log.warn("복용 인증 이미지 보상 삭제 실패: imageUrl={}", imageUrl, exception);
            }
        }
    }
}
