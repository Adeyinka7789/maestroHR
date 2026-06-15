/**
 * MaestroHR — Tailwind config encoding the Stitch "MaestroHR Design System" tokens.
 * Source of truth: design/stitch_reference/maestrohr_design_system/DESIGN.md
 *                  design/stitch_reference/hr_admin_dashboard_desktop/code.html
 *
 * MIGRATION SCOPE (server-rendered fragments):
 *  - `content` scans only the migrated fragments (dashboard, employees), so the
 *    compiled stylesheet stays small and contains nothing the other pages reference.
 *  - `corePlugins.preflight: false` — no global element reset. The output is purely
 *    additive utility classes, so loading it alongside the existing Tailwind CDN does
 *    NOT restyle any other page. Later passes widen `content` and drop the CDN.
 */
module.exports = {
  content: [
    "./src/main/resources/templates/dashboard.html",
    "./src/main/resources/templates/employees.html",
  ],
  darkMode: "class",
  corePlugins: {
    preflight: false,
  },
  theme: {
    extend: {
      colors: {
        "tertiary-container": "#004565",
        "on-secondary-fixed": "#002114",
        "surface-dim": "#cbdbf5",
        "surface-bright": "#f8f9ff",
        "secondary": "#006c4a",
        "on-secondary": "#ffffff",
        "on-primary-fixed-variant": "#264191",
        "secondary-fixed-dim": "#68dba9",
        "on-tertiary": "#ffffff",
        "on-primary-container": "#90a8ff",
        "error-container": "#ffdad6",
        "surface-tint": "#4059aa",
        "surface-container-lowest": "#ffffff",
        "on-surface-variant": "#444651",
        "error": "#ba1a1a",
        "on-primary": "#ffffff",
        "on-error-container": "#93000a",
        "tertiary-fixed-dim": "#89ceff",
        "secondary-container": "#82f5c1",
        "surface": "#f8f9ff",
        "secondary-fixed": "#85f8c4",
        "inverse-surface": "#213145",
        "tertiary-fixed": "#c9e6ff",
        "on-primary-fixed": "#00164e",
        "primary-fixed": "#dce1ff",
        "surface-container-highest": "#d3e4fe",
        "surface-container-low": "#eff4ff",
        "tertiary": "#002e44",
        "surface-container": "#e5eeff",
        "primary-container": "#1e3a8a",
        "on-tertiary-container": "#36b6fb",
        "inverse-on-surface": "#eaf1ff",
        "primary-fixed-dim": "#b6c4ff",
        "background": "#f8f9ff",
        "on-error": "#ffffff",
        "on-background": "#0b1c30",
        "primary": "#00236f",
        "outline": "#757682",
        "on-secondary-container": "#00714e",
        "on-tertiary-fixed-variant": "#004c6e",
        "outline-variant": "#c5c5d3",
        "on-tertiary-fixed": "#001e2f",
        "on-secondary-fixed-variant": "#005137",
        "inverse-primary": "#b6c4ff",
        "surface-variant": "#d3e4fe",
        "surface-container-high": "#dce9ff",
        "on-surface": "#0b1c30",
      },
      borderRadius: {
        DEFAULT: "0.25rem",
        lg: "0.5rem",
        xl: "0.75rem",
        full: "9999px",
      },
      spacing: {
        xs: "4px",
        gutter: "24px",
        xl: "64px",
        "margin-desktop": "32px",
        lg: "40px",
        md: "24px",
        base: "8px",
        sm: "12px",
        "margin-mobile": "16px",
        "container-max": "1280px",
      },
      fontFamily: {
        "label-md": ["Inter", "sans-serif"],
        "label-sm": ["Inter", "sans-serif"],
        "headline-md": ["Hanken Grotesk", "sans-serif"],
        "headline-lg": ["Hanken Grotesk", "sans-serif"],
        "table-data": ["Inter", "sans-serif"],
        "body-lg": ["Inter", "sans-serif"],
        "headline-sm": ["Hanken Grotesk", "sans-serif"],
        "body-md": ["Inter", "sans-serif"],
        "body-sm": ["Inter", "sans-serif"],
        "display-lg": ["Hanken Grotesk", "sans-serif"],
      },
      fontSize: {
        "label-md": ["14px", { lineHeight: "1", letterSpacing: "0.01em", fontWeight: "500" }],
        "label-sm": ["12px", { lineHeight: "1", fontWeight: "600" }],
        "headline-md": ["24px", { lineHeight: "1.3", fontWeight: "600" }],
        "headline-lg": ["32px", { lineHeight: "1.25", fontWeight: "600" }],
        "table-data": ["13px", { lineHeight: "1.4", fontWeight: "400" }],
        "body-lg": ["18px", { lineHeight: "1.5", fontWeight: "400" }],
        "headline-sm": ["20px", { lineHeight: "1.4", fontWeight: "600" }],
        "body-md": ["16px", { lineHeight: "1.5", fontWeight: "400" }],
        "body-sm": ["14px", { lineHeight: "1.5", fontWeight: "400" }],
        "display-lg": ["48px", { lineHeight: "1.2", letterSpacing: "-0.02em", fontWeight: "700" }],
      },
    },
  },
  plugins: [],
};
