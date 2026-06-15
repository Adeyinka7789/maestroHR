---
name: MaestroHR Design System
colors:
  surface: '#f8f9ff'
  surface-dim: '#cbdbf5'
  surface-bright: '#f8f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eff4ff'
  surface-container: '#e5eeff'
  surface-container-high: '#dce9ff'
  surface-container-highest: '#d3e4fe'
  on-surface: '#0b1c30'
  on-surface-variant: '#444651'
  inverse-surface: '#213145'
  inverse-on-surface: '#eaf1ff'
  outline: '#757682'
  outline-variant: '#c5c5d3'
  surface-tint: '#4059aa'
  primary: '#00236f'
  on-primary: '#ffffff'
  primary-container: '#1e3a8a'
  on-primary-container: '#90a8ff'
  inverse-primary: '#b6c4ff'
  secondary: '#006c4a'
  on-secondary: '#ffffff'
  secondary-container: '#82f5c1'
  on-secondary-container: '#00714e'
  tertiary: '#002e44'
  on-tertiary: '#ffffff'
  tertiary-container: '#004565'
  on-tertiary-container: '#36b6fb'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#dce1ff'
  primary-fixed-dim: '#b6c4ff'
  on-primary-fixed: '#00164e'
  on-primary-fixed-variant: '#264191'
  secondary-fixed: '#85f8c4'
  secondary-fixed-dim: '#68dba9'
  on-secondary-fixed: '#002114'
  on-secondary-fixed-variant: '#005137'
  tertiary-fixed: '#c9e6ff'
  tertiary-fixed-dim: '#89ceff'
  on-tertiary-fixed: '#001e2f'
  on-tertiary-fixed-variant: '#004c6e'
  background: '#f8f9ff'
  on-background: '#0b1c30'
  surface-variant: '#d3e4fe'
typography:
  display-lg:
    fontFamily: Hanken Grotesk
    fontSize: 48px
    fontWeight: '700'
    lineHeight: '1.2'
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Hanken Grotesk
    fontSize: 32px
    fontWeight: '600'
    lineHeight: '1.25'
  headline-md:
    fontFamily: Hanken Grotesk
    fontSize: 24px
    fontWeight: '600'
    lineHeight: '1.3'
  headline-sm:
    fontFamily: Hanken Grotesk
    fontSize: 20px
    fontWeight: '600'
    lineHeight: '1.4'
  body-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '400'
    lineHeight: '1.5'
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.5'
  body-sm:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: '1.5'
  label-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '500'
    lineHeight: '1'
    letterSpacing: 0.01em
  label-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: '1'
  table-data:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '400'
    lineHeight: '1.4'
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  xs: 4px
  sm: 12px
  md: 24px
  lg: 40px
  xl: 64px
  container-max: 1280px
  gutter: 24px
  margin-mobile: 16px
  margin-desktop: 32px
---

## Brand & Style

The design system is anchored in the principles of **Stability, Precision, and Growth**. As an HR and Payroll solution tailored for the Nigerian market, it must bridge the gap between rigorous financial compliance and a user-friendly employee experience.

The visual style is **Corporate / Modern**. It prioritizes extreme legibility and a sense of "organized calm" to reduce the anxiety typically associated with payroll processing and tax compliance. By utilizing a clean, systematic approach with ample whitespace and a disciplined color palette, the UI facilitates focus on high-priority tasks like disbursement approvals and employee onboarding. The aesthetic is professional yet forward-thinking, reflecting the digital transformation of Nigerian enterprises.

## Colors

The color palette is led by **Deep Blue (#1E3A8A)**, a color that commands trust and signals institutional reliability—essential for a platform handling sensitive financial data. This is balanced by **Emerald Green (#059669)**, which is used strategically for "Success" states and financial growth indicators, resonating with the vibrant fintech landscape in Nigeria.

- **Primary Blue:** Used for navigation, primary actions, and brand-heavy elements.
- **Secondary Green:** Used for "Pay Now" actions, "Paid" status indicators, and positive growth charts.
- **Neutrals:** A slate-based gray scale (from #0F172A to #F8FAFC) ensures that text contrast is optimal for long-form data reading.
- **Semantic Colors:** Critical for payroll; Failed disbursements must be immediately identifiable via the Error Red, while "Draft" or "Pending" states use the Neutral Slate or Warning Amber.

## Typography

This design system uses a dual-font strategy. **Hanken Grotesk** is used for headlines to provide a sharp, modern, and authoritative voice. **Inter** is used for body copy and data tables due to its exceptional legibility at small sizes and high X-height, which is critical for payroll software where users spend hours looking at numbers and names.

For mobile devices, `display-lg` should scale down to `headline-lg`, and all `headline` levels should decrease by one tier (e.g., `headline-lg` becomes `headline-md` on mobile). Numeric data in tables should ideally use tabular lining (if supported by the font) to ensure decimal points align perfectly for easy visual scanning.

## Layout & Spacing

The layout philosophy is based on a **12-column Fluid Grid** with fixed maximum widths for desktop to prevent line lengths from becoming unreadable on ultra-wide monitors. 

A strict **8px base unit** governs all spatial relationships. 
- **Dashboards:** Use `lg` (40px) spacing between major card sections to maintain an "airy" feel that reduces the perceived complexity of the data.
- **Data Tables:** Use a tighter "Compact" vertical rhythm (using `xs` and `sm` units) to ensure maximum information density without sacrificing clarity.
- **Mobile:** Elements reflow to a single column. The side navigation collapses into a bottom bar or a hamburger menu, and side margins reduce to 16px to maximize screen real estate for data tables.

## Elevation & Depth

To maintain a clean and professional look, this design system avoids heavy shadows in favor of **Tonal Layers** and **Low-Contrast Outlines**.

- **Level 0 (Canvas):** The base background (#F8FAFC).
- **Level 1 (Surface):** Cards and main content areas (#FFFFFF) with a 1px border (#E2E8F0).
- **Level 2 (Interactive):** Elements like dropdowns or hovered cards use a subtle ambient shadow (0px 4px 12px rgba(30, 58, 138, 0.05)) to suggest "lift."
- **Level 3 (Overlay):** Modals and slide-outs use a slightly deeper shadow and a background backdrop blur (8px) to focus the user’s attention on the task at hand.

Depth is primarily communicated through color shifts (e.g., a subtle gray background for a sidebar vs. a white background for the main content) rather than dramatic shadows.

## Shapes

The shape language uses a **Rounded (0.5rem / 8px)** base. This radius provides a modern, approachable feel while remaining structured enough for a professional enterprise tool.

- **Buttons & Input Fields:** 8px radius.
- **Status Badges:** Fully pill-shaped (rounded-full) to distinguish them from interactive buttons.
- **Dashboard Cards:** 16px (`rounded-lg`) to create a softer, containerized feel for high-level summaries.
- **Selection Controls:** Checkboxes use a 4px radius, while Radio buttons remain circular.

## Components

### Buttons
- **Primary:** Deep Blue background, white text. No gradient. High-contrast.
- **Secondary:** Transparent background, Deep Blue 1px border and text.
- **Success:** Emerald Green background for "Approve" or "Run Payroll."

### Status Badges
Status badges use a light tint of the semantic color for the background and a dark shade for the text (e.g., **FAILED** uses a light red background with dark red text). They are always uppercase and use `label-sm` typography.

### Data Tables
Tables are the heart of this design system. They must feature:
- Sticky headers for long lists.
- Zebra striping (using #F8FAFC) for rows.
- Right-aligned numeric columns (for currency/salaries).
- Inline "Quick Actions" that appear on row hover.

### Cards
Cards are used for "Total Payroll," "Employee Count," and "Upcoming Tax Deadlines." They feature a 1px #E2E8F0 border and use `headline-md` for the primary metric.

### Input Fields
Standardized height of 44px. Labels are always visible above the field (never just placeholders). Focus state uses a 2px Emerald Green ring to indicate activity without being overwhelming.