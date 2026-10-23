import { execSync } from 'node:child_process';
import { pluginQRCode } from '@lynx-js/qrcode-rsbuild-plugin';
import { pluginReactLynx } from '@lynx-js/react-rsbuild-plugin';
import { defineConfig, type RsbuildPlugin } from '@lynx-js/rspeedy';
import { pluginTypeCheck } from '@rsbuild/plugin-type-check';

const PORT = 3000;
const PROD = process.env.NODE_ENV === 'production';
const APP_ID = 'net.coursehunter.app';

const androidDevices = () =>
  execSync('adb devices')
    .toString()
    .split('\n')
    .slice(1)
    .filter((l) => l.endsWith('\tdevice'))
    .map((l) => l.split('\t')[0]);

// With ANDROID_RELOAD=1, launch the debug app on first build and re-render it on every rebuild.
const pluginAndroidReload = (): RsbuildPlugin => ({
  name: 'android-reload',
  setup(api) {
    if (!process.env.ANDROID_RELOAD) return;
    api.onDevCompileDone(({ isFirstCompile }) => {
      for (const s of androidDevices()) {
        try {
          if (isFirstCompile) {
            execSync(`adb -s ${s} reverse tcp:${PORT} tcp:${PORT}`);
            execSync(`adb -s ${s} shell am start -n ${APP_ID}/.MainActivity`);
          } else {
            execSync(
              `adb -s ${s} shell am broadcast -a ${APP_ID}.RELOAD -p ${APP_ID}`,
            );
          }
        } catch (e) {
          console.warn(`[android-reload] ${s}: ${(e as Error).message}`);
        }
      }
    });
  },
});

export default defineConfig({
  dev: {
    hmr: true,
    liveReload: true,
  },
  // Lynx Android can't load relative asset URLs from the packaged bundle; inline small images.
  output: {
    dataUriLimit: { image: 16 * 1024 },
  },
  // Release bundles ship without devtools, debug checks or console output.
  resolve: PROD
    ? {
        alias: {
          '@lynx-js/preact-devtools': false,
          '@lynx-js/react/debug': false,
        },
      }
    : {},
  performance: {
    removeConsole: PROD,
  },
  server: {
    port: PORT,
    strictPort: true,
  },
  plugins: [
    process.env.ANDROID_RELOAD
      ? pluginAndroidReload()
      : pluginQRCode({
          schema(url) {
            // We use `?fullscreen=true` to open the page in LynxExplorer in full screen mode
            return `${url}?fullscreen=true`;
          },
        }),
    pluginReactLynx(),
    pluginTypeCheck(),
  ],
});
