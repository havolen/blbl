# Launcher artwork

Source: https://dashboardicons.com/icons/bilibili

Original SVG: https://raw.githubusercontent.com/homarr-labs/dashboard-icons/main/svg/bilibili.svg

The original pink `#EC5D85`, light pink `#EF85A7`, shadow pink `#EB5480`, white wordmark and television shape are retained. The brand palette is independent of the app's theme tokens.

- Normal icon: edge-to-edge pink canvas, with the reference foreground at 88% for safe margins. No transparent outer inset; launcher masks determine the rounded rectangle.
- Round icon: pink circle with the original foreground inset to keep the wordmark visible.
- Adaptive icon: pink background and original foreground inside a 72dp region of the 108dp canvas.
- TV banner: 1280×720, edge-to-edge pink `#EC5D85` background, original foreground centered at 620×620 without distortion.

Regenerate using Node.js with `sharp` available: `node generate-icons.cjs`. No image-generation dependency is needed by Gradle. `launcher-preview.png` and `res/drawable-nodpi/tv_banner.png` are previews.
