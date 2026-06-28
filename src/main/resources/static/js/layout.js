// layout.js – runs once, persists across HTMX navigations
(function () {
    // ── Auth from localStorage ─────────────────────────────────────
    const token       = localStorage.getItem('maestrohr_token');
    const userEmail   = localStorage.getItem('maestrohr_email')   || '';
    const userRole    = localStorage.getItem('maestrohr_role')    || '';
    const companyName = localStorage.getItem('maestrohr_company') || '';

    // Decode a JWT's exp claim and report whether it has passed. A malformed token is
    // treated as expired; a token with no exp claim is treated as still valid.
    function isJwtExpired(jwt) {
        try {
            const payload = JSON.parse(
                atob(jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
            return typeof payload.exp === 'number' && Date.now() >= payload.exp * 1000;
        } catch {
            return true;
        }
    }

    // Clear stale auth and bounce to login. Single helper so every auth-failure path
    // (page load, HTMX response, background apiCall) behaves identically.
    function redirectToLogin() {
        localStorage.clear();
        window.location.href = '/login';
    }

    // Redirect to login if the token is missing OR already expired (skip login/register
    // pages). Checking expiry here avoids firing requests that would just 401/403.
    const onAuthPage = window.location.pathname.startsWith('/login')
                    || window.location.pathname.startsWith('/register');
    if (!onAuthPage && (!token || isJwtExpired(token))) {
        redirectToLogin();
        return;
    }

    // Redirect root to HTMX dashboard
    if (window.location.pathname === '/') {
        window.location.href = userRole === 'SUPER_ADMIN' ? '/htmx/admin' : '/htmx/dashboard';
        return;
    }

    // ── Global MaestroHR object – used by all page partials ─────────
    window.MaestroHR = {
        token,
        userEmail,
        userRole,

        apiCall(url, options = {}) {
            return fetch(url, {
                ...options,
                credentials: 'same-origin',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`,
                    ...(options.headers || {})
                }
            }).then(res => {
                // Token expired/revoked mid-session: bail to login instead of letting
                // callers render an error from the JSON 401/403 body.
                if (res.status === 401 || res.status === 403) redirectToLogin();
                return res;
            });
        },

        escapeHtml(text) {
            if (!text) return '';
            const div = document.createElement('div');
            div.textContent = String(text);
            return div.innerHTML;
        },

        showToast(message, type = 'info') {
            const icons = {
                success: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>',
                error:   '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>',
                info:    '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>'
            };
            const toast = document.createElement('div');
            toast.className = `toast toast-${type}`;
            toast.innerHTML = `${icons[type] || icons.info} <span>${message}</span>`;
            document.getElementById('toast-container').appendChild(toast);
            setTimeout(() => toast.remove(), 4000);
        }
    };

    // ── Current date in topbar ─────────────────────────────────────
    document.getElementById('current-date').textContent =
        new Date().toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });

    // ── Sidebar user info ─────────────────────────────────────────
    if (userEmail) {
        document.getElementById('sidebar-avatar').textContent = userEmail.charAt(0).toUpperCase();
        document.getElementById('sidebar-email').textContent  = userEmail;
        document.getElementById('sidebar-role').textContent   = userRole.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
    }
    if (companyName) document.getElementById('sidebar-tenant').textContent = companyName;

    // Show admin section for SUPER_ADMIN only; hide HR nav
    if (userRole === 'SUPER_ADMIN') {
        const adminSection = document.getElementById('admin-section');
        if (adminSection) adminSection.style.display = 'block';
        const hrSections = document.getElementById('hr-sections');
        if (hrSections) hrSections.style.display = 'none';
    }

    // EMPLOYEE sees only their own pages; hide all HR/admin nav items
    if (userRole === 'EMPLOYEE') {
        const allowed = new Set(['dashboard', 'leave', 'attendance-me', 'payslips', 'loans-me']);
        const hrSections = document.getElementById('hr-sections');
        if (hrSections) {
            hrSections.querySelectorAll('.nav-item').forEach(el => {
                if (!allowed.has(el.dataset.route)) el.style.display = 'none';
            });
            // Hide section labels whose nav items are all hidden
            hrSections.querySelectorAll('.nav-section').forEach(section => {
                let sib = section.nextElementSibling;
                let hasVisible = false;
                while (sib && !sib.classList.contains('nav-section')) {
                    if (sib.classList.contains('nav-item') && sib.style.display !== 'none') {
                        hasVisible = true;
                        break;
                    }
                    sib = sib.nextElementSibling;
                }
                if (!hasVisible) section.style.display = 'none';
            });
        }
    }

    // Show User Management nav only for SYSTEM_ADMIN (SUPER_ADMIN sees their own console instead)
    if (userRole === 'SYSTEM_ADMIN') {
        const usersLink = document.querySelector('.nav-item[data-route="users"]');
        if (usersLink) usersLink.style.display = '';
    }

    // Hide audit log link for non‑authorized roles
    if (userRole !== 'HR_ADMIN' && userRole !== 'SUPER_ADMIN' && userRole !== 'SYSTEM_ADMIN') {
        const auditLink = document.querySelector('a[data-route="audit"]');
        if (auditLink) auditLink.style.display = 'none';
    }

    // ── Impersonation banner (Feature 4) ───────────────────────────
    // When the active token carries impersonation=true, show the red banner and wire Exit to
    // restore the admin session stashed in sessionStorage by the impersonation picker.
    (function setupImpersonationBanner() {
        function decodeJwt(jwt) {
            try {
                return JSON.parse(atob(jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
            } catch { return null; }
        }
        const payload = token ? decodeJwt(token) : null;
        if (!payload || payload.impersonation !== true) return;

        const banner = document.getElementById('impersonation-banner');
        const text   = document.getElementById('impersonation-banner-text');
        if (!banner || !text) return;

        const who   = userEmail || payload.sub || 'user';
        const where = companyName ? ` (${companyName})` : '';
        text.textContent = `Impersonating ${who}${where}`;
        banner.style.display = 'flex';

        // Restore the admin's own session from sessionStorage and bounce back to the picker.
        function restoreAdminSession() {
            const adminToken = sessionStorage.getItem('__adminToken');
            if (!adminToken) { redirectToLogin(); return; }
            localStorage.setItem('maestrohr_token',   adminToken);
            localStorage.setItem('maestrohr_email',   sessionStorage.getItem('__adminEmail')   || '');
            localStorage.setItem('maestrohr_role',    sessionStorage.getItem('__adminRole')    || '');
            localStorage.setItem('maestrohr_tenant',  sessionStorage.getItem('__adminTenant')  || '');
            localStorage.setItem('maestrohr_company', sessionStorage.getItem('__adminCompany') || '');
            const secure = window.location.protocol === 'https:' ? '; Secure' : '';
            document.cookie = 'maestrohr_token=' + encodeURIComponent(adminToken)
                + '; Path=/; Max-Age=' + (60 * 60 * 24 * 7) + '; SameSite=Lax' + secure;
            ['__adminToken', '__adminEmail', '__adminRole', '__adminTenant', '__adminCompany']
                .forEach(k => sessionStorage.removeItem(k));
            window.location.href = '/htmx/admin/impersonation';
        }

        document.getElementById('impersonation-exit-btn')?.addEventListener('click', async () => {
            // Log the exit event with the impersonation token, then restore the admin session no
            // matter how the call resolves (use a raw fetch so MaestroHR.apiCall's 401/403→login
            // bounce can't strip the stashed admin token before we restore it).
            try {
                await fetch('/api/admin/impersonate/exit', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'Authorization': `Bearer ${token}` }
                });
            } catch { /* ignore — restore regardless */ }
            restoreAdminSession();
        });
    })();

    // ── Sidebar pin (persist in localStorage) ─────────────────
    if (localStorage.getItem('sidebar-pinned') === 'true') {
        document.body.classList.add('sidebar-pinned');
    }
    document.getElementById('sidebar-toggle')?.addEventListener('click', () => {
        const pinned = document.body.classList.toggle('sidebar-pinned');
        localStorage.setItem('sidebar-pinned', String(pinned));
    });

    // ── Logout ─────────────────────────────────────────────────────
    document.getElementById('logout-btn').addEventListener('click', () => {
        localStorage.clear();
        window.location.href = '/login';
    });

    // ── Active nav highlighting (for /htmx/ routes) ─────────
    function updateActiveNav() {
        // Get the path without leading slash, and strip /htmx/ prefix if present
        let path = window.location.pathname.replace(/^\//, '');
        if (path.startsWith('htmx/')) {
            path = path.substring(5); // remove 'htmx/'
        }
        // attendance/me and loans/me have their own route keys so EMPLOYEE nav filtering /
        // active-highlighting can target them (otherwise they'd collapse to 'attendance'/'loans')
        let currentRoute;
        if (path === 'attendance/me') {
            currentRoute = 'attendance-me';
        } else if (path === 'loans/me') {
            currentRoute = 'loans-me';
        } else {
            currentRoute = path.split('/')[0] || 'dashboard';
        }

        document.querySelectorAll('.nav-item').forEach(el => {
            el.classList.toggle('active', el.dataset.route === currentRoute);
        });
        // Update topbar heading and page title
        const activeLink = document.querySelector(`.nav-item[data-route="${currentRoute}"]`);
        if (activeLink) {
            const label = activeLink.querySelector('.nav-label')?.textContent
                          || activeLink.dataset.label
                          || 'MaestroHR';
            const heading = document.getElementById('page-heading');
            if (heading) heading.textContent = label;
            document.getElementById('page-title-tag').textContent = `${label} — MaestroHR`;
        }
    }
    updateActiveNav();
    document.body.addEventListener('htmx:afterSwap', () => updateActiveNav());

    // ── Redirect to login on auth failure from any HTMX request ─────
    // Covers nav clicks / fragment swaps that 401/403 after the JWT expires: HTMX fires
    // htmx:responseError for non-2xx, so we bounce to login rather than swapping the
    // JSON error body into the page.
    document.body.addEventListener('htmx:responseError', function (evt) {
        const status = evt.detail.xhr.status;
        if (status === 401 || status === 403) redirectToLogin();
    });

    // ── Global search ──────────────────────────────────────────────
    let searchTimer;
    const searchInput   = document.getElementById('global-search-input');
    const searchResults = document.getElementById('global-search-results');

    if (searchInput) {
        searchInput.addEventListener('input', () => {
            clearTimeout(searchTimer);
            const query = searchInput.value.trim();
            if (query.length < 2) {
                searchResults.style.display = 'none';
                return;
            }
            searchTimer = setTimeout(async () => {
                try {
                    const res = await MaestroHR.apiCall(`/api/search?q=${encodeURIComponent(query)}`);
                    const data = await res.json();
                    const results = data?.data?.results || [];
                    searchResults.innerHTML = results.length === 0
                        ? '<div style="padding:14px;color:#64748b;font-size:12px;">No results found.</div>'
                        : results.map(item => `
                            <a class="search-result-link" href="${item.url}">
                                <div style="font-size:11px;text-transform:uppercase;color:#3b82f6;font-weight:600;">${MaestroHR.escapeHtml(item.type)}</div>
                                <div style="font-size:13px;font-weight:600;margin-top:2px;">${MaestroHR.escapeHtml(item.title)}</div>
                                <div style="font-size:12px;color:#64748b;margin-top:3px;">${MaestroHR.escapeHtml(item.subtitle)}</div>
                            </a>`).join('');
                    searchResults.style.display = 'block';
                } catch {
                    searchResults.innerHTML = '<div style="padding:14px;color:#64748b;font-size:12px;">Search failed.</div>';
                    searchResults.style.display = 'block';
                }
            }, 300);
        });
        document.addEventListener('click', e => {
            if (!searchInput.contains(e.target) && !searchResults?.contains(e.target)) {
                searchResults.style.display = 'none';
            }
        });
    }

    // ── Notifications ──────────────────────────────────────────────
    async function fetchNotifications() {
        try {
            const [listRes, countRes] = await Promise.all([
                MaestroHR.apiCall('/api/notifications'),
                MaestroHR.apiCall('/api/notifications/unread-count')
            ]);
            const listData  = await listRes.json();
            const countData = await countRes.json();
            const notifications = listData.data || [];
            const unreadCount   = countData.data?.count || 0;

            const badge = document.getElementById('notification-badge');
            badge.style.display = unreadCount > 0 ? 'block' : 'none';
            if (unreadCount > 0) badge.textContent = unreadCount > 9 ? '9+' : unreadCount;

            const listEl = document.getElementById('notification-list');
            if (listEl) {
                listEl.innerHTML = notifications.length === 0
                    ? '<div style="padding:20px 14px;color:#64748b;font-size:12.5px;text-align:center;">No notifications</div>'
                    : notifications.map(n => `
                        <div class="notification-item ${n.read ? '' : 'unread'}">
                            <div style="font-weight:600;font-size:13px;color:#111827;">${MaestroHR.escapeHtml(n.title)}</div>
                            <div style="font-size:12px;color:#6b7280;margin-top:3px;">${MaestroHR.escapeHtml(n.message)}</div>
                            <div style="font-size:11px;color:#9ca3af;margin-top:5px;">${new Date(n.createdAt).toLocaleString()}</div>
                            ${!n.read ? `<button class="mark-read-btn" data-id="${n.id}" style="background:none;border:none;color:#2563eb;font-size:11px;cursor:pointer;margin-top:5px;">Mark as read</button>` : ''}
                        </div>`).join('');
            }

            document.querySelectorAll('.mark-read-btn').forEach(btn => {
                btn.addEventListener('click', async () => {
                    await MaestroHR.apiCall(`/api/notifications/${btn.dataset.id}/read`, { method: 'POST' });
                    fetchNotifications();
                });
            });
        } catch (err) {
            console.error('Notifications error:', err);
        }
    }

    const notificationToggle = document.getElementById('notification-toggle');
    const notificationPanel  = document.getElementById('notification-panel');

    notificationToggle?.addEventListener('click', () => {
        const open = notificationPanel.style.display === 'block';
        notificationPanel.style.display = open ? 'none' : 'block';
        if (!open) fetchNotifications();
    });

    document.getElementById('mark-all-read-btn')?.addEventListener('click', async () => {
        await MaestroHR.apiCall('/api/notifications/read-all', { method: 'POST' });
        fetchNotifications();
    });

    document.addEventListener('click', e => {
        if (notificationPanel && !notificationPanel.contains(e.target) && !notificationToggle.contains(e.target)) {
            notificationPanel.style.display = 'none';
        }
    });

    if (token) fetchNotifications();

    // ── Platform broadcasts (Feature 5) ────────────────────────────
    // Unread platform announcements, filtered server-side to this tenant's plan tier, shown as
    // dismissible banners above the topbar. Dismiss = POST .../read, which removes it for good.
    async function fetchBroadcasts() {
        const container = document.getElementById('broadcast-banner-container');
        if (!container) return;
        try {
            const res = await MaestroHR.apiCall('/api/broadcasts/unread');
            const data = await res.json();
            const broadcasts = (data && data.data) || [];
            if (broadcasts.length === 0) {
                container.style.display = 'none';
                container.innerHTML = '';
                return;
            }
            container.innerHTML = broadcasts.map(b => `
                <div class="broadcast-banner" data-id="${b.id}"
                     style="display:flex; align-items:flex-start; justify-content:space-between; gap:16px; background:#eff6ff; border-bottom:1px solid #bfdbfe; color:#1e3a8a; padding:9px 24px;">
                    <div style="display:flex; align-items:flex-start; gap:10px; min-width:0;">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" style="flex-shrink:0; margin-top:2px;"><path d="M3 11l18-5v12L3 14v-3z"/><path d="M11.6 16.8a3 3 0 1 1-5.8-1.6"/></svg>
                        <div style="min-width:0;">
                            <span style="font-size:13px; font-weight:600;">${MaestroHR.escapeHtml(b.title)}</span>
                            <span style="font-size:12.5px; color:#1d4ed8; margin-left:6px;">${MaestroHR.escapeHtml(b.body)}</span>
                        </div>
                    </div>
                    <button type="button" class="broadcast-dismiss-btn" data-id="${b.id}" title="Dismiss"
                            style="background:none; border:none; cursor:pointer; color:#1e40af; padding:2px; flex-shrink:0; line-height:0;">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                </div>`).join('');
            container.style.display = 'block';

            container.querySelectorAll('.broadcast-dismiss-btn').forEach(btn => {
                btn.addEventListener('click', async () => {
                    const id = btn.dataset.id;
                    btn.disabled = true;
                    try {
                        await MaestroHR.apiCall(`/api/broadcasts/${id}/read`, { method: 'POST' });
                    } catch (e) { /* removed below regardless; it will reappear on reload if it truly failed */ }
                    const row = container.querySelector(`.broadcast-banner[data-id="${id}"]`);
                    if (row) row.remove();
                    if (!container.querySelector('.broadcast-banner')) container.style.display = 'none';
                });
            });
        } catch (err) {
            console.error('Broadcasts error:', err);
        }
    }

    if (token) fetchBroadcasts();

    // ── Platform feature flags — hide nav items for disabled features ──────────
    async function applyFeatureFlags() {
        try {
            const res = await MaestroHR.apiCall('/api/features/active');
            const data = await res.json();
            const active = new Set(data.data || []);
            const hrSections = document.getElementById('hr-sections');
            if (!hrSections) return;
            hrSections.querySelectorAll('.nav-item[data-feature]').forEach(el => {
                if (!active.has(el.dataset.feature)) el.style.display = 'none';
            });
            // Hide section labels whose nav items are all hidden after this pass
            hrSections.querySelectorAll('.nav-section').forEach(section => {
                let sib = section.nextElementSibling;
                let hasVisible = false;
                while (sib && !sib.classList.contains('nav-section')) {
                    if (sib.classList.contains('nav-item') && sib.style.display !== 'none') {
                        hasVisible = true;
                        break;
                    }
                    sib = sib.nextElementSibling;
                }
                if (!hasVisible) section.style.display = 'none';
            });
        } catch (err) {
            console.error('Feature flags error:', err);
        }
    }

    if (token) applyFeatureFlags();

    // ── Trial expiry banner (SYSTEM_ADMIN only) ─────────────────────────────
    // Calls /api/auth/me (already authenticated) and reads daysRemainingInTrial.
    // Yellow banner <= 7 days; red banner when trial has ended (<= 0).
    if (userRole === 'SYSTEM_ADMIN') {
        (async function checkTrialBanner() {
            try {
                const res = await MaestroHR.apiCall('/api/auth/me');
                if (!res.ok) return;
                const data = await res.json();
                const days = data?.data?.daysRemainingInTrial;
                if (days == null) return; // not trialing — nothing to show
                const banner = document.getElementById('trial-banner');
                if (!banner) return;
                if (days <= 0) {
                    banner.innerHTML = `
                        <div style="display:flex; align-items:center; justify-content:space-between; gap:16px; background:#fef2f2; border-bottom:1px solid #fecaca; color:#7f1d1d; padding:9px 24px;">
                            <div style="display:flex; align-items:center; gap:10px; font-size:13px; font-weight:500;">
                                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" style="flex-shrink:0;"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
                                Your free trial has ended.
                            </div>
                            <a href="/htmx/plans" style="background:#dc2626; color:white; font-size:12.5px; font-weight:600; padding:5px 14px; border-radius:6px; text-decoration:none; flex-shrink:0;">Upgrade to continue →</a>
                        </div>`;
                } else if (days <= 7) {
                    banner.innerHTML = `
                        <div style="display:flex; align-items:center; justify-content:space-between; gap:16px; background:#fffbeb; border-bottom:1px solid #fde68a; color:#78350f; padding:9px 24px;">
                            <div style="display:flex; align-items:center; gap:10px; font-size:13px; font-weight:500;">
                                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" style="flex-shrink:0;"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                                Your free trial ends in <strong style="margin:0 3px;">${days}</strong> day${days === 1 ? '' : 's'}.
                            </div>
                            <a href="/htmx/plans" style="background:#d97706; color:white; font-size:12.5px; font-weight:600; padding:5px 14px; border-radius:6px; text-decoration:none; flex-shrink:0;">Upgrade now →</a>
                        </div>`;
                }
                if (days <= 7) banner.style.display = 'block';
            } catch (err) {
                console.error('Trial banner error:', err);
            }
        })();
    }

    // ── Onboarding wizard (defined in onboarding-wizard.js, loaded before this file) ──
    window.MaestroHR.initOnboarding = window.__MaestroOnboardingInit || function () {};
    if (token) window.MaestroHR.initOnboarding();

    // ── Auto‑load content for the current route ─────────────────
    (function autoLoadInitialContent() {
        const currentPath = window.location.pathname;
        // App pages live at /htmx/<route>. The post-login landing /dashboard is the
        // one bare route a user reaches directly, so map it to its /htmx/ partial —
        // this matches how every other page is loaded on direct visit / refresh.
        const contentUrl = currentPath === '/dashboard' ? '/htmx/dashboard' : currentPath;
        if (contentUrl.startsWith('/htmx/')) {
            fetch(contentUrl, {
                headers: { 'HX-Request': 'true' }
            })
            .then(response => {
                // Session expired between page load and this fetch: go to login rather
                // than rendering the JSON 401/403 error into the content area.
                if (response.status === 401 || response.status === 403) {
                    redirectToLogin();
                    return null;
                }
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                return response.text();
            })
            .then(html => {
                if (html === null) return;
                const contentDiv = document.getElementById('page-content');
                if (contentDiv) {
                    contentDiv.innerHTML = html;
                    // Re‑execute any scripts inside the loaded content
                    const scripts = contentDiv.querySelectorAll('script');
                    scripts.forEach(oldScript => {
                        const newScript = document.createElement('script');
                        if (oldScript.src) {
                            newScript.src = oldScript.src;
                        } else {
                            newScript.textContent = oldScript.textContent;
                        }
                        document.body.appendChild(newScript);
                        oldScript.remove();
                    });
                    // Activate HTMX on the injected fragment. This raw-fetch path sets
                    // innerHTML directly (not via an HTMX swap), so without this the
                    // nested hx-* triggers (e.g. the employees search/filter controls)
                    // would never be registered and clicking/typing would do nothing.
                    if (window.htmx) htmx.process(contentDiv);
                    // Update active nav and page title after content loads
                    updateActiveNav();
                }
            })
            .catch(err => {
                console.error('Auto-load failed:', err);
                document.getElementById('page-content').innerHTML = '<div class="text-center py-12 text-red-500">Failed to load content. Please refresh or click a sidebar link.</div>';
            });
        }
    })();

    // ── Skeleton loader on HTMX requests ─────────────────────────────
    const contentArea = document.getElementById('page-content');

    function showSkeleton() {
        const skeletonHtml = `
            <div class="space-y-4">
                <div class="skeleton skeleton-header"></div>
                <div class="grid grid-cols-4 gap-4">
                    <div class="skeleton skeleton-stat"></div>
                    <div class="skeleton skeleton-stat"></div>
                    <div class="skeleton skeleton-stat"></div>
                    <div class="skeleton skeleton-stat"></div>
                </div>
                <div class="skeleton-card mt-6">
                    <div class="skeleton skeleton-line"></div>
                    <div class="skeleton skeleton-line"></div>
                    <div class="skeleton skeleton-line w-3/4"></div>
                </div>
            </div>
        `;
        if (contentArea) contentArea.innerHTML = skeletonHtml;
    }

    // Show skeleton only for full-page swaps (target IS #page-content). In-page
    // fragment swaps that target a descendant — e.g. the employees list swapping
    // #employees-table on search/filter/paging — must NOT trigger the skeleton:
    // showSkeleton() replaces all of #page-content, which would destroy the inner
    // swap target before the response lands, leaving the skeleton stuck forever.
    document.body.addEventListener('htmx:beforeRequest', function(evt) {
        const target = evt.detail.target;
        if (target && target.id === 'page-content') {
            showSkeleton();
        }
    });

    // Optional: hide skeleton after swap (not needed because content replaces it)

    // ── Conditional confirm for payroll-impacting Mark Attendance (Step C) ──────────
    // htmx fires htmx:confirm before EVERY request. We only intervene for elements tagged
    // data-confirm-absent (the Mark Attendance / per-row Edit forms): if the form's chosen
    // status is ABSENT — the only status that reduces pay — we take over and prompt; for any
    // other status (or any other request) we return early and let htmx proceed as normal, so
    // this never interferes with the built-in hx-confirm used elsewhere (e.g. leave approve).
    document.body.addEventListener('htmx:confirm', function (evt) {
        const elt = evt.detail.elt;
        if (!elt || !elt.matches('[data-confirm-absent]')) return;
        const scope = elt.closest('form') || elt;
        const status = scope.querySelector('[name="status"]')?.value;
        if (status !== 'ABSENT') return; // not pay-impacting — proceed without a prompt
        evt.preventDefault();            // we own the confirm; htmx waits for issueRequest
        if (window.confirm('Marking this employee ABSENT will reduce their pay for this period. Continue?')) {
            evt.detail.issueRequest(true); // true = skip any further confirm processing
        }
    });

    // ── PWA: offline / online detection ──────────────────────────────────────
    (function initOfflineDetection() {
        let offlineBanner = null;

        function getOrCreateBanner() {
            if (offlineBanner) return offlineBanner;
            offlineBanner = document.createElement('div');
            offlineBanner.id = 'offline-banner';
            offlineBanner.style.cssText = [
                'position:fixed', 'top:0', 'left:0', 'right:0', 'z-index:9999',
                'background:#b45309', 'color:#fff', 'text-align:center',
                'font-size:.875rem', 'font-weight:500', 'padding:.45rem 1rem',
                'transition:transform .25s ease'
            ].join(';');
            offlineBanner.textContent = "You're offline — some features may be limited";
            document.body.prepend(offlineBanner);
            return offlineBanner;
        }

        function showOfflineBanner() {
            const banner = getOrCreateBanner();
            banner.style.transform = 'translateY(0)';
        }

        function hideOfflineBanner() {
            if (offlineBanner) offlineBanner.style.transform = 'translateY(-100%)';
        }

        window.isOnline = function () { return navigator.onLine; };

        window.addEventListener('offline', function () {
            showOfflineBanner();
        });

        window.addEventListener('online', function () {
            hideOfflineBanner();
            if (window.showToast) window.showToast('Back online!', 'success');
            if ('serviceWorker' in navigator && 'SyncManager' in window) {
                navigator.serviceWorker.ready.then(function (reg) {
                    reg.sync.register('attendance-sync').catch(function () {});
                });
            }
        });

        if (!navigator.onLine) showOfflineBanner();
    })();

    // ── Mobile bottom nav ─────────────────────────────────────────────
    function navigateTo(route, path) {
        htmx.ajax('GET', path, { target: '#page-content', swap: 'innerHTML' });
        history.pushState({}, '', path);
    }

    function setMobileActive(activeId) {
        ['mob-home', 'mob-quick', 'mob-notifications'].forEach(function (id) {
            var el = document.getElementById(id);
            if (el) el.classList.toggle('active', id === activeId);
        });
    }

    function initMobileNav() {
        if (window.innerWidth > 768) return;

        var quickActions = {
            'HR_ADMIN':       { icon: 'group',          label: 'Employees',  route: 'employees' },
            'SYSTEM_ADMIN':   { icon: 'group',          label: 'Employees',  route: 'employees' },
            'EMPLOYEE':       { icon: 'fingerprint',    label: 'Attendance', route: 'attendance-me' },
            'FINANCE_OFFICER':{ icon: 'payments',       label: 'Payroll',    route: 'payroll' },
            'DEPT_MANAGER':   { icon: 'event_available',label: 'Leave',      route: 'leave' },
            'SUPER_ADMIN':    { icon: 'corporate_fare', label: 'Tenants',    route: 'subscribers' }
        };

        var qa = quickActions[userRole] || quickActions['HR_ADMIN'];
        document.getElementById('mob-quick-icon').textContent  = qa.icon;
        document.getElementById('mob-quick-label').textContent = qa.label;
        document.getElementById('mob-quick').dataset.route     = qa.route;

        document.getElementById('mob-home').addEventListener('click', function () {
            navigateTo('dashboard', '/htmx/dashboard');
            setMobileActive('mob-home');
        });

        document.getElementById('mob-quick').addEventListener('click', function () {
            navigateTo(qa.route, '/htmx/' + qa.route.replace('-me', '/me'));
            setMobileActive('mob-quick');
        });

        document.getElementById('mob-notifications').addEventListener('click', function () {
            var toggle = document.getElementById('notification-toggle');
            if (toggle) toggle.click();
            setMobileActive('mob-notifications');
        });

        document.getElementById('mob-menu').addEventListener('click', function () {
            var toggle = document.getElementById('mobile-menu-toggle');
            if (toggle) toggle.click();
        });

        function syncNotifBadge() {
            var badge    = document.getElementById('notification-badge');
            var mobBadge = document.getElementById('mob-notif-badge');
            if (badge && mobBadge) {
                var count = badge.textContent.trim();
                if (count && count !== '0' && badge.style.display !== 'none') {
                    mobBadge.textContent = count;
                    mobBadge.style.display = 'block';
                } else {
                    mobBadge.style.display = 'none';
                }
            }
        }

        syncNotifBadge();
        setInterval(syncNotifBadge, 30000);

        document.body.addEventListener('htmx:pushedIntoHistory', function (e) {
            var path = e.detail.path || '';
            if (path.includes('dashboard'))    setMobileActive('mob-home');
            else if (path.includes(qa.route))  setMobileActive('mob-quick');
            else                               setMobileActive(null);
        });
    }

    initMobileNav();
})();