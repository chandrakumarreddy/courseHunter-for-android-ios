import { useCallback, useEffect, useRef, useState } from '@lynx-js/react';
import type { NodesRef } from '@lynx-js/types';

import './App.css';
import { Button } from './components/Button/Button.js';

const SITE_URL = 'https://coursehunter.net/';
const TOAST_DURATION_MS = 4000;

const loadingOverlay = (
  <view className="Overlay">
    <text className="SplashLogo">ch.</text>
    <view className="Track">
      <view className="Bar" />
    </view>
  </view>
);

type WebviewErrorDetail = {
  errorCode?: number;
  errCode?: number;
  errorMsg?: string;
  errMsg?: string;
};

export function App() {
  const webviewRef = useRef<NodesRef>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const loadedRef = useRef(false);

  const onLoad = useCallback(() => {
    loadedRef.current = true;
    setLoading(false);
    setError(null);
  }, []);

  const onError = useCallback((e: { detail?: WebviewErrorDetail }) => {
    const d = e.detail ?? {};
    const message =
      `${d.errorCode ?? d.errCode ?? ''} ${d.errorMsg ?? d.errMsg ?? 'Failed to load'}`.trim();
    // Sub-resource failures (e.g. video range requests) also fire `error`; only block on initial load.
    if (loadedRef.current) {
      if (__DEV__) console.warn('webview error', message);
      return;
    }
    setLoading(false);
    setError(message);
  }, []);

  const reload = useCallback(() => {
    'background only';
    loadedRef.current = false;
    setError(null);
    setLoading(true);
    webviewRef.current?.invoke({ method: 'reload' }).exec();
  }, []);

  useEffect(() => {
    if (!error) return;
    const timer = setTimeout(() => setError(null), TOAST_DURATION_MS);
    return () => clearTimeout(timer);
  }, [error]);

  return (
    <view className="Screen">
      <webview
        ref={webviewRef}
        className="Web"
        src={SITE_URL}
        enable-debug={__DEV__}
        bindload={onLoad}
        binderror={onError}
      />
      {loading && loadingOverlay}
      {error && (
        <view className="Toast">
          <text className="ToastText">{error}</text>
          <Button className="ToastAction" onTap={reload}>
            <text className="ToastActionText">Retry</text>
          </Button>
        </view>
      )}
    </view>
  );
}
