package com.myide.backend.service.design.ai;

import com.myide.backend.domain.notification.NotificationType;
import com.myide.backend.dto.design.v2.DesignDraftJobResponse;
import com.myide.backend.dto.design.v2.DesignDraftRequest;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.DoctorReport;
import com.myide.backend.dto.design.v2.TechStackV2;
import com.myide.backend.service.NotificationService;
import com.myide.backend.service.design.doctor.DesignDoctorService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 초안을 요청과 떨어뜨려 서버가 끝까지 만든다.
 *
 * 예전에는 브라우저가 두 단계를 차례로 직접 불렀다. 그래서 만드는 동안
 * 창을 닫거나 다른 페이지로 가면 결과가 그대로 버려졌다. 이제는 서버가
 * 작업을 맡고, 끝나면 알림을 남긴다. 화면은 상태만 물어본다.
 *
 * 작업은 메모리에만 둔다. 서버를 다시 켜면 사라지지만 잃는 것은 아직
 * 문서에 들어가지 않은 초안뿐이라, 다시 만들면 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DesignDraftJobService {

    /** 찾아가지 않은 결과를 이만큼만 들고 있는다. */
    private static final Duration KEEP = Duration.ofHours(1);

    private final DesignDraftService draftService;
    private final DesignDoctorService doctorService;
    private final NotificationService notificationService;

    /** 사용자·워크스페이스마다 하나. 같은 사람이 두 벌을 동시에 만들 일은 없다. */
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private static final class Job {
        final Instant createdAt = Instant.now();
        volatile String status = "RUNNING";
        volatile int stage = 1;
        volatile DesignModelV2 model;
        volatile DoctorReport report;
        volatile String errorMessage;

        DesignDraftJobResponse toResponse() {
            return new DesignDraftJobResponse(status, stage, model, report, errorMessage);
        }
    }

    public DesignDraftJobResponse start(Long userId, String workspaceId, DesignDraftRequest request) {
        Instant expiry = Instant.now().minus(KEEP);
        jobs.values().removeIf(job -> job.createdAt.isBefore(expiry));

        Job created = new Job();

        // 이미 만드는 중이면 그것을 그대로 돌려준다. 버튼을 두 번 눌렀다고
        // AI 를 두 번 부르지 않는다.
        Job current = jobs.compute(key(userId, workspaceId), (key, existing) ->
                existing != null && "RUNNING".equals(existing.status) ? existing : created);

        if (current == created) {
            executor.submit(() -> run(created, userId, workspaceId, request));
        }

        return current.toResponse();
    }

    /** 없으면 null. */
    public DesignDraftJobResponse get(Long userId, String workspaceId) {
        Job job = jobs.get(key(userId, workspaceId));
        return job == null ? null : job.toResponse();
    }

    public void discard(Long userId, String workspaceId) {
        jobs.remove(key(userId, workspaceId));
    }

    private void run(Job job, Long userId, String workspaceId, DesignDraftRequest request) {
        String targetUrl = "/design?workspaceId=" + workspaceId;

        try {
            TechStackV2 stack = request.techStack() == null
                    ? TechStackV2.empty()
                    : request.techStack();

            DesignModelV2 skeleton = draftService.generateSkeleton(
                    request.summary(), stack, request.instruction(), request.existing());

            job.stage = 2;

            DesignModelV2 model = draftService.generateDetail(
                    skeleton, request.instruction(), request.existing());

            // status 를 마지막에 바꾼다. 먼저 바꾸면 화면이 결과 없는 "완료"를 받아 간다.
            job.report = doctorService.inspect(model);
            job.model = model;
            job.status = "DONE";

            notify(userId, workspaceId, NotificationType.SYSTEM, "AI 초안 생성 완료",
                    "설계 초안이 준비되었습니다. 확인하고 문서에 넣어 주세요.", targetUrl);
        } catch (Exception e) {
            // 서비스가 이유를 사람이 읽을 문장으로 담아 던지므로 그대로 옮긴다.
            String reason = e instanceof ResponseStatusException status && status.getReason() != null
                    ? status.getReason()
                    : "초안을 만들지 못했습니다. 다시 시도해 주세요.";

            if (!(e instanceof ResponseStatusException)) {
                log.error("⚠️ [AI] 초안 작업이 예상 밖의 오류로 끝났습니다.", e);
            }

            job.errorMessage = reason;
            job.status = "FAILED";

            notify(userId, workspaceId, NotificationType.ERROR, "AI 초안 생성 실패", reason, targetUrl);
        }
    }

    /** 알림을 못 남겼다고 다 만든 초안까지 실패로 만들지 않는다. */
    private void notify(Long userId, String workspaceId, NotificationType type,
                        String title, String message, String targetUrl) {
        try {
            notificationService.notifyUser(userId, workspaceId, type, title, message, targetUrl);
        } catch (Exception e) {
            log.warn("⚠️ [AI] 초안 알림을 남기지 못했습니다: {}", e.getMessage());
        }
    }

    private String key(Long userId, String workspaceId) {
        return userId + ":" + workspaceId;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
