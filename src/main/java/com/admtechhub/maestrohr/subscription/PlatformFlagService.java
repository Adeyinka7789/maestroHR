package com.admtechhub.maestrohr.subscription;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages global {@link PlatformFlag} kill switches. Platform-wide (not tenant-scoped):
 * a SUPER_ADMIN toggles a flag and it takes effect for every tenant.
 *
 * <p><b>Default-enabled semantics:</b> a flag with no row is treated as enabled. This keeps
 * every existing feature working unchanged — only features explicitly seeded/flagged (today
 * just {@code LOAN_MANAGEMENT}) are subject to a global switch. {@link #enable}/{@link #disable}
 * create the row on first toggle so a never-seeded feature can still be switched off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformFlagService {

    private final PlatformFlagRepository flagRepository;

    /** Whether the named flag is on. Absent flag → {@code true} (backward compatible). */
    @Transactional(readOnly = true)
    public boolean isEnabled(String flagName) {
        return flagRepository.findByName(flagName)
                .map(PlatformFlag::isEnabled)
                .orElse(true);
    }

    /** Turn the flag on, creating it if it does not yet exist. */
    @Transactional
    public PlatformFlag enable(String flagName, String updatedBy) {
        return setEnabled(flagName, true, updatedBy);
    }

    /** Turn the flag off, creating it if it does not yet exist. */
    @Transactional
    public PlatformFlag disable(String flagName, String updatedBy) {
        return setEnabled(flagName, false, updatedBy);
    }

    @Transactional(readOnly = true)
    public List<PlatformFlag> listAll() {
        return flagRepository.findAllByOrderByNameAsc();
    }

    private PlatformFlag setEnabled(String flagName, boolean enabled, String updatedBy) {
        PlatformFlag flag = flagRepository.findByName(flagName)
                .orElseGet(() -> PlatformFlag.builder().name(flagName).build());
        flag.setEnabled(enabled);
        flag.setUpdatedBy(updatedBy);
        PlatformFlag saved = flagRepository.save(flag);
        log.info("Platform flag '{}' set to enabled={} by {}", flagName, enabled, updatedBy);
        return saved;
    }
}
