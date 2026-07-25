package com.admtechhub.maestrohr.recruitment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public, unauthenticated careers portal ({@code /careers/**}, allowlisted in
 * {@link com.admtechhub.maestrohr.auth.PublicPaths#NO_TENANT}). Server-rendered Thymeleaf so the
 * pages are crawlable and shareable. All data access goes through {@link CareersService} /
 * {@link CareersPublicRepository} on the privileged datasource, since the request carries no
 * tenant session.
 *
 * <p>Because the apply endpoint is a public write, it has three cheap abuse guards: a hidden
 * honeypot field (bots fill it; humans never see it), a per-IP submission rate limit, and the
 * per-email dedup enforced in {@link CareersService}. Heavier protection (captcha) is a follow-up.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class CareersPublicController {

    private final CareersService careersService;

    // Per-IP sliding-window limiter for the public apply endpoint. In-memory and best-effort:
    // it throttles casual abuse from a single host; it is not a substitute for an edge WAF.
    private static final int MAX_SUBMITS_PER_WINDOW = 5;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(10);
    private static final Map<String, Deque<Long>> SUBMITS_BY_IP = new ConcurrentHashMap<>();

    @GetMapping("/careers/{slug}")
    public String landing(@PathVariable String slug, Model model) {
        try {
            CareersView.Listing listing = careersService.loadListing(slug);
            model.addAttribute("company", listing.company());
            model.addAttribute("jobs", listing.jobs());
            model.addAttribute("slug", slug);
            return "careers/index";
        } catch (CareersService.CareersUnavailableException e) {
            model.addAttribute("reason", e.getMessage());
            return "careers/unavailable";
        }
    }

    @GetMapping("/careers/{slug}/jobs/{jobId}")
    public String jobDetail(@PathVariable String slug, @PathVariable UUID jobId, Model model) {
        try {
            CareersService.JobDetail detail = careersService.loadJob(slug, jobId);
            model.addAttribute("company", detail.company());
            model.addAttribute("job", detail.job());
            model.addAttribute("slug", slug);
            return "careers/job";
        } catch (CareersService.CareersUnavailableException | CareersService.JobNotFoundException e) {
            model.addAttribute("reason", e.getMessage());
            return "careers/unavailable";
        }
    }

    @PostMapping("/careers/{slug}/jobs/{jobId}/apply")
    public String apply(@PathVariable String slug,
                        @PathVariable UUID jobId,
                        @RequestParam String applicantName,
                        @RequestParam String applicantEmail,
                        @RequestParam(required = false) String applicantPhone,
                        @RequestParam(required = false) String coverLetter,
                        @RequestParam(required = false) MultipartFile resume,
                        // Honeypot: a field hidden from real users via CSS. Any value = a bot.
                        @RequestParam(required = false) String website,
                        HttpServletRequest request,
                        HttpServletResponse response,
                        Model model) {

        // Tripped honeypot: pretend success, persist nothing.
        if (website != null && !website.isBlank()) {
            log.info("Careers honeypot tripped for slug {} job {} — dropping submission", slug, jobId);
            model.addAttribute("slug", slug);
            return "careers/apply-success";
        }

        if (rateLimited(clientIp(request))) {
            response.setStatus(429); // Too Many Requests (no servlet constant for it)
            return renderJobWithError(slug, jobId,
                    "You've submitted several applications recently. Please try again later.",
                    applicantName, applicantEmail, applicantPhone, coverLetter, model);
        }

        try {
            careersService.submitApplication(slug, jobId, applicantName, applicantEmail,
                    applicantPhone, coverLetter, resume);
            model.addAttribute("slug", slug);
            return "careers/apply-success";
        } catch (CareersService.CareersUnavailableException | CareersService.JobNotFoundException e) {
            model.addAttribute("reason", e.getMessage());
            return "careers/unavailable";
        } catch (CareersService.ApplicationRejectedException e) {
            return renderJobWithError(slug, jobId, e.getMessage(),
                    applicantName, applicantEmail, applicantPhone, coverLetter, model);
        }
    }

    /** Re-render the job/apply page with an error banner and the values the candidate entered. */
    private String renderJobWithError(String slug, UUID jobId, String error,
                                      String applicantName, String applicantEmail,
                                      String applicantPhone, String coverLetter, Model model) {
        try {
            CareersService.JobDetail detail = careersService.loadJob(slug, jobId);
            model.addAttribute("company", detail.company());
            model.addAttribute("job", detail.job());
            model.addAttribute("slug", slug);
            model.addAttribute("error", error);
            model.addAttribute("applicantName", applicantName);
            model.addAttribute("applicantEmail", applicantEmail);
            model.addAttribute("applicantPhone", applicantPhone);
            model.addAttribute("coverLetter", coverLetter);
            return "careers/job";
        } catch (CareersService.CareersUnavailableException | CareersService.JobNotFoundException e) {
            model.addAttribute("reason", e.getMessage());
            return "careers/unavailable";
        }
    }

    private static boolean rateLimited(String ip) {
        long now = System.currentTimeMillis();
        long cutoff = now - RATE_WINDOW.toMillis();
        Deque<Long> hits = SUBMITS_BY_IP.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst() < cutoff) {
                hits.pollFirst();
            }
            if (hits.size() >= MAX_SUBMITS_PER_WINDOW) {
                return true;
            }
            hits.addLast(now);
            return false;
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
