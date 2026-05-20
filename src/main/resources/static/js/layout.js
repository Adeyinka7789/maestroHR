// layout.js – runs once, persists across HTMX navigations
(function () {
    // ── Auth from localStorage ─────────────────────────────────────
    const token       = localStorage.getItem('maestrohr_token');
    const userEmail   = localStorage.getItem('maestrohr_email')   || '';
    const userRole    = localStorage.getItem('maestrohr_role')    || '';
    const companyName = localStorage.getItem('maestrohr_company') || '';

    // Redirect to login if no token (skip login/register pages)
    if (!token && !window.location.pathname.startsWith('/login') && !window.location.pathname.startsWith('/register')) {
        window.location.href = '/login';
        return;
    }

    // Redirect root to HTMX dashboard
    if (window.location.pathname === '/') {
        window.location.href = '/htmx/dashboard';
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

    // Show admin section for SUPER_ADMIN only
    if (userRole === 'SUPER_ADMIN') {
        const adminSection = document.getElementById('admin-section');
        if (adminSection) adminSection.style.display = 'block';
    }

    // Hide audit log link for non‑authorized roles
    if (userRole !== 'HR_ADMIN' && userRole !== 'SUPER_ADMIN') {
        const auditLink = document.querySelector('a[data-route="audit"]');
        if (auditLink) auditLink.style.display = 'none';
    }

    // ── Sidebar collapse (persist in localStorage) ─────────────────
    if (localStorage.getItem('maestrohr_sidebar') === 'collapsed') {
        document.body.classList.add('sidebar-collapsed');
    }
    document.getElementById('sidebar-toggle')?.addEventListener('click', () => {
        document.body.classList.toggle('sidebar-collapsed');
        localStorage.setItem('maestrohr_sidebar',
            document.body.classList.contains('sidebar-collapsed') ? 'collapsed' : 'open');
    });

    // ── Logout ─────────────────────────────────────────────────────
    document.getElementById('logout-btn').addEventListener('click', () => {
        localStorage.clear();
        window.location.href = '/login';
    });

    // Custom tooltip for collapsed sidebar
    let tooltipTimeout;
    const tooltipDiv = document.createElement('div');
    tooltipDiv.className = 'sidebar-tooltip';
    tooltipDiv.style.cssText = `
        position: fixed;
        background: #1e293b;
        color: #e2e8f0;
        padding: 5px 11px;
        border-radius: 6px;
        font-size: 12.5px;
        white-space: nowrap;
        z-index: 10000;
        pointer-events: none;
        border: 1px solid #334155;
        box-shadow: 0 4px 14px rgba(0,0,0,0.35);
        display: none;
        font-family: 'DM Sans', sans-serif;
    `;
    document.body.appendChild(tooltipDiv);

    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('mouseenter', (e) => {
            if (!document.body.classList.contains('sidebar-collapsed')) return;
            const label = item.getAttribute('data-label');
            if (!label) return;
            tooltipDiv.textContent = label;
            const rect = item.getBoundingClientRect();
            tooltipDiv.style.left = (rect.right + 10) + 'px';
            tooltipDiv.style.top = (rect.top + (rect.height / 2) - 15) + 'px';
            tooltipDiv.style.display = 'block';
            clearTimeout(tooltipTimeout);
        });
        item.addEventListener('mouseleave', () => {
            tooltipTimeout = setTimeout(() => {
                tooltipDiv.style.display = 'none';
            }, 100);
        });
    });

    // ── Active nav highlighting (for /htmx/ routes) ─────────
    function updateActiveNav() {
        // Get the path without leading slash, and strip /htmx/ prefix if present
        let path = window.location.pathname.replace(/^\//, '');
        if (path.startsWith('htmx/')) {
            path = path.substring(5); // remove 'htmx/'
        }
        const currentRoute = path.split('/')[0] || 'dashboard';

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

    // ── Auto‑load content for the current HTMX route ─────────────────
    (function autoLoadInitialContent() {
        const currentPath = window.location.pathname;
        // If we are on an HTMX route (starts with /htmx/), fetch the content partial
        if (currentPath.startsWith('/htmx/')) {
            const contentUrl = currentPath;
            fetch(contentUrl, {
                headers: { 'HX-Request': 'true' }
            })
            .then(response => {
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                return response.text();
            })
            .then(html => {
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

    // Show skeleton before any HTMX request targeting #page-content
    document.body.addEventListener('htmx:beforeRequest', function(evt) {
        const target = evt.detail.target;
        if (target && (target.id === 'page-content' || target.closest('#page-content'))) {
            showSkeleton();
        }
    });

    // Optional: hide skeleton after swap (not needed because content replaces it)
})();