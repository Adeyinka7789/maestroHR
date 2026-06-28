// onboarding-wizard.js — Role-based first-login walkthrough
// Loaded before layout.js. Exposes window.__MaestroOnboardingInit which layout.js
// picks up, attaches to MaestroHR.initOnboarding, and calls after auth setup.
(function () {

    var STEPS = {
        SYSTEM_ADMIN: [
            {
                icon: '👑',
                title: 'Welcome — you\'re the account owner',
                description: 'You\'ve just set up MaestroHR for your company. As account owner, you have full control over users, employees, payroll, and settings.',
                tip: 'Start by inviting your HR team so they can help manage day-to-day operations.'
            },
            {
                icon: '👥',
                title: 'Invite your team',
                description: 'Add HR admins, finance officers, and managers who need access to the system. Each person gets a role-appropriate view of the platform.',
                tip: 'Go to User Management → Add User to invite a team member.'
            },
            {
                icon: '🧑‍💼',
                title: 'Add your employees',
                description: 'Build your employee directory. Import in bulk via CSV or add individually. Employees can log in to view payslips and apply for leave.',
                tip: 'Go to Employees → Import CSV or Add Employee to get started.'
            },
            {
                icon: '💰',
                title: 'Run your first payroll',
                description: 'Once employees have pay grades, you\'re ready to compute payroll. MaestroHR handles PAYE, deductions, and net pay automatically.',
                tip: 'Go to Payroll Runs → New Payroll Run to compute and approve.'
            }
        ],
        HR_ADMIN: [
            {
                icon: '👋',
                title: 'Welcome to MaestroHR',
                description: 'As HR Admin, you manage the full employee lifecycle — onboarding, payroll, and leave. Here\'s a quick tour of your key tools.',
                tip: 'Your dashboard shows a live summary of headcount, attendance, and pending requests.'
            },
            {
                icon: '🧑‍💼',
                title: 'Add your employees',
                description: 'Build the employee directory. Import in bulk via CSV or add individually. Employees get login access to view payslips and apply for leave.',
                tip: 'Go to Employees → Add Employee or Import CSV.'
            },
            {
                icon: '📅',
                title: 'Manage leave requests',
                description: 'Review and approve leave applications from your team. Set leave policies and track balances per employee.',
                tip: 'Go to Leave Management to view pending requests.'
            },
            {
                icon: '💰',
                title: 'Run payroll',
                description: 'Compute payroll for the month, review the breakdown, and approve disbursement. PAYE and deductions are calculated automatically.',
                tip: 'Go to Payroll Runs → New Payroll Run.'
            }
        ],
        FINANCE_OFFICER: [
            {
                icon: '👋',
                title: 'Welcome to MaestroHR',
                description: 'As Finance Officer, you oversee payroll approval and financial reporting. Here\'s what you\'ll do most.',
                tip: 'Your dashboard highlights payroll runs awaiting your approval.'
            },
            {
                icon: '✅',
                title: 'Approve payroll runs',
                description: 'Review computed payroll runs before disbursement. Check gross pay, deductions, and PAYE figures for accuracy.',
                tip: 'Go to Payroll Runs to see runs ready for your approval.'
            },
            {
                icon: '📊',
                title: 'Download reports',
                description: 'Generate payroll summaries, PAYE reports, and deduction breakdowns. Export to PDF or Excel for records and audits.',
                tip: 'Go to Reports to generate and download your reports.'
            }
        ],
        DEPT_MANAGER: [
            {
                icon: '👋',
                title: null, // set dynamically from me.departmentName
                description: 'As Department Manager, you oversee your team\'s attendance and leave. You\'ll be notified of requests that need your action.',
                tip: 'You can see only your team\'s data — other departments are not visible to you.'
            },
            {
                icon: '📋',
                title: 'See your team\'s attendance',
                description: 'Track who\'s checked in, who\'s absent, and view attendance trends for your department.',
                tip: 'Go to Attendance to see today\'s status and history.'
            },
            {
                icon: '📅',
                title: 'Approve leave requests',
                description: 'Your team members\' leave requests land here for your approval. Review, approve, or reject with a note.',
                tip: 'Go to Leave Management to see pending requests.'
            }
        ],
        EMPLOYEE: [
            {
                icon: '👋',
                title: 'Welcome to MaestroHR',
                description: 'Your HR team has set you up on MaestroHR. This is your personal workspace for payslips, leave, and attendance.',
                tip: 'Everything here is just for you — your data stays private.'
            },
            {
                icon: '👤',
                title: 'Complete your profile',
                description: 'Review and update your personal details, contact information, and bank account for payroll.',
                tip: 'Go to My Profile to check and update your information.'
            },
            {
                icon: '🏖️',
                title: 'Apply for leave',
                description: 'Submit leave requests and track their status. Your leave balance is shown automatically.',
                tip: 'Go to Leave Management → New Leave Request.'
            },
            {
                icon: '💸',
                title: 'Check your payslips',
                description: 'View and download your monthly payslips once payroll is processed by your HR team.',
                tip: 'Go to My Payslips to see your payment history.'
            }
        ]
    };

    var FINAL_ROUTE = {
        SYSTEM_ADMIN:    '/htmx/users',
        HR_ADMIN:        '/htmx/employees',
        FINANCE_OFFICER: '/htmx/payroll',
        DEPT_MANAGER:    '/htmx/attendance',
        EMPLOYEE:        '/htmx/profile'
    };

    function escHtml(s) {
        var d = document.createElement('div');
        d.textContent = s || '';
        return d.innerHTML;
    }

    async function initOnboarding() {
        var path = window.location.pathname;
        if (path.startsWith('/login') || path.startsWith('/register')) return;

        var token = localStorage.getItem('maestrohr_token');
        if (!token) return;

        try {
            var res = await fetch('/api/auth/me', {
                credentials: 'same-origin',
                headers: { 'Authorization': 'Bearer ' + token }
            });
            if (!res.ok) return;
            var body = await res.json();
            var me = body.data;
            if (!me || me.hasCompletedOnboarding || me.role === 'SUPER_ADMIN') return;

            var steps = STEPS[me.role];
            if (!steps) return;

            // Clone so we don't mutate the shared constant
            steps = steps.map(function (s) { return Object.assign({}, s); });

            if (me.role === 'DEPT_MANAGER' && steps[0].title === null) {
                steps[0].title = 'Welcome — you manage ' + (me.departmentName || 'your team');
            }

            showWizard(steps, FINAL_ROUTE[me.role] || '/htmx/dashboard', token);
        } catch (e) {
            // Non-critical — silently skip
        }
    }

    function showWizard(steps, finalRoute, token) {
        var total   = steps.length;
        var current = 0;

        /* ── Overlay ── */
        var overlay = document.createElement('div');
        overlay.id = 'onboarding-overlay';
        overlay.setAttribute('style', [
            'position:fixed;inset:0;z-index:9999',
            'background:rgba(0,0,0,0.65)',
            'display:flex;align-items:center;justify-content:center',
            'padding:16px',
            'opacity:0;transition:opacity 0.3s ease'
        ].join(';'));

        /* ── Card ── */
        var card = document.createElement('div');
        card.setAttribute('style', [
            'background:white;border-radius:20px',
            'width:90%;max-width:520px',
            'padding:40px 36px',
            'position:relative;overflow:hidden',
            'box-shadow:0 20px 60px rgba(0,0,0,0.25)',
            'font-family:Inter,"DM Sans",sans-serif'
        ].join(';'));

        /* ── Progress dots ── */
        var dotsEl = document.createElement('div');
        dotsEl.setAttribute('style', 'display:flex;justify-content:center;gap:8px;margin-bottom:28px;');

        /* ── Step content wrapper (fixed-height sliding area) ── */
        var stepsWrapper = document.createElement('div');
        stepsWrapper.setAttribute('style', 'position:relative;overflow:hidden;min-height:220px;');

        /* ── Bottom navigation ── */
        var nav = document.createElement('div');
        nav.setAttribute('style', 'display:flex;align-items:center;justify-content:space-between;margin-top:28px;gap:12px;');

        var skipBtn = document.createElement('button');
        skipBtn.textContent = 'Skip tour';
        skipBtn.setAttribute('style', [
            'background:none;border:none;cursor:pointer',
            'font-size:13px;color:#6b7280;font-family:inherit',
            'padding:8px 4px;border-radius:6px;flex-shrink:0',
            'transition:color 0.15s'
        ].join(';'));
        skipBtn.addEventListener('mouseenter', function () { skipBtn.style.color = '#374151'; });
        skipBtn.addEventListener('mouseleave', function () { skipBtn.style.color = '#6b7280'; });

        var counterEl = document.createElement('span');
        counterEl.setAttribute('style', 'font-size:12px;color:#9ca3af;font-family:inherit;text-align:center;flex:1;');

        var nextBtn = document.createElement('button');
        nextBtn.setAttribute('style', [
            'background:#111827;color:white;border:none;cursor:pointer',
            'font-size:13px;font-weight:600;font-family:inherit',
            'padding:10px 20px;border-radius:10px;flex-shrink:0',
            'transition:background 0.15s'
        ].join(';'));
        nextBtn.addEventListener('mouseenter', function () { nextBtn.style.background = '#374151'; });
        nextBtn.addEventListener('mouseleave', function () { nextBtn.style.background = '#111827'; });

        nav.appendChild(skipBtn);
        nav.appendChild(counterEl);
        nav.appendChild(nextBtn);

        card.appendChild(dotsEl);
        card.appendChild(stepsWrapper);
        card.appendChild(nav);
        overlay.appendChild(card);
        document.body.appendChild(overlay);

        // Fade in on next paint
        requestAnimationFrame(function () { overlay.style.opacity = '1'; });

        /* ── Dot rendering ── */
        function renderDots() {
            dotsEl.innerHTML = '';
            for (var i = 0; i < total; i++) {
                var dot = document.createElement('div');
                dot.setAttribute('style', i === current
                    ? 'width:24px;height:8px;border-radius:4px;background:#111827;transition:all 0.3s ease;flex-shrink:0;'
                    : 'width:8px;height:8px;border-radius:50%;background:#e5e7eb;transition:all 0.3s ease;flex-shrink:0;');
                dotsEl.appendChild(dot);
            }
        }

        function renderCounter() {
            counterEl.textContent = (current + 1) + ' of ' + total;
        }

        function renderNextLabel() {
            nextBtn.textContent = current === total - 1 ? 'Get Started →' : 'Next →';
        }

        /* ── Step element factory ── */
        function buildStepEl(step) {
            var el = document.createElement('div');
            el.setAttribute('style', 'padding:0;');
            el.innerHTML = [
                '<div style="text-align:center;margin-bottom:20px;">',
                '  <span style="font-size:52px;line-height:1;display:block;margin-bottom:14px;">',
                      step.icon,
                '  </span>',
                '  <h2 style="font-size:19px;font-weight:700;color:#111827;margin:0 0 10px;line-height:1.35;">',
                      escHtml(step.title),
                '  </h2>',
                '  <p style="font-size:14px;color:#4b5563;margin:0;line-height:1.75;">',
                      escHtml(step.description),
                '  </p>',
                '</div>',
                '<div style="background:#f0f9ff;border:1px solid #bae6fd;border-radius:10px;padding:14px 16px;">',
                '  <p style="font-size:13px;color:#0369a1;margin:0;line-height:1.65;">',
                '    <strong>💡 Tip:</strong> ', escHtml(step.tip),
                '  </p>',
                '</div>'
            ].join('');
            return el;
        }

        var activeEl = null;

        function goToStep(index, direction) {
            var newEl = buildStepEl(steps[index]);
            var fromX  = direction === 'next' ? '100%' : '-100%';
            var exitX  = direction === 'next' ? '-100%' : '100%';
            newEl.setAttribute('style', [
                'position:absolute;top:0;left:0;right:0',
                'transform:translateX(' + fromX + ')',
                'transition:transform 0.3s ease'
            ].join(';'));
            stepsWrapper.appendChild(newEl);

            if (activeEl) {
                var outgoing = activeEl;
                outgoing.style.transform = 'translateX(' + exitX + ')';
                outgoing.addEventListener('transitionend', function () { outgoing.remove(); }, { once: true });
            }

            // Double rAF so the browser registers the starting transform before transitioning
            requestAnimationFrame(function () {
                requestAnimationFrame(function () {
                    newEl.style.transform = 'translateX(0)';
                });
            });
            activeEl = newEl;

            // Expand wrapper to fit new content after it has rendered
            setTimeout(function () {
                var h = newEl.scrollHeight;
                if (h > 0) stepsWrapper.style.minHeight = h + 'px';
            }, 60);

            renderDots();
            renderCounter();
            renderNextLabel();
        }

        /* ── Initial render (no slide animation) ── */
        var firstEl = buildStepEl(steps[0]);
        firstEl.setAttribute('style', 'position:absolute;top:0;left:0;right:0;');
        stepsWrapper.appendChild(firstEl);
        activeEl = firstEl;
        setTimeout(function () {
            var h = firstEl.scrollHeight;
            if (h > 0) stepsWrapper.style.minHeight = h + 'px';
        }, 60);
        renderDots();
        renderCounter();
        renderNextLabel();

        /* ── Dismiss (fade out, call API, then remove) ── */
        async function dismiss(navigate) {
            try {
                await fetch('/api/auth/onboarding/complete', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'Authorization': 'Bearer ' + token }
                });
            } catch (e) { /* ignore — non-critical */ }
            overlay.style.opacity = '0';
            overlay.addEventListener('transitionend', function () {
                overlay.remove();
                if (navigate) window.location.href = navigate;
            }, { once: true });
        }

        /* ── Button handlers ── */
        nextBtn.addEventListener('click', function () {
            if (current < total - 1) {
                current++;
                goToStep(current, 'next');
            } else {
                dismiss(finalRoute);
            }
        });

        skipBtn.addEventListener('click', function () { dismiss(null); });

        // Backdrop tap = skip
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) dismiss(null);
        });

        /* ── Touch / swipe support ── */
        var touchStartX = 0;
        card.addEventListener('touchstart', function (e) {
            touchStartX = e.touches[0].clientX;
        }, { passive: true });
        card.addEventListener('touchend', function (e) {
            var dx = e.changedTouches[0].clientX - touchStartX;
            if (Math.abs(dx) < 50) return;
            if (dx < 0 && current < total - 1) { current++; goToStep(current, 'next'); }
            else if (dx > 0 && current > 0)    { current--; goToStep(current, 'back'); }
        }, { passive: true });
    }

    window.__MaestroOnboardingInit = initOnboarding;
})();
