import type { ReactNode } from '@lynx-js/react';
import type { MainThread } from '@lynx-js/types';

type ButtonProps = {
  className?: string;
  onTap: () => void;
  children: ReactNode;
};

// Press feedback runs on the main thread so it shows on the touch frame; the tap itself is handled in the background.
function press(e: MainThread.TouchEvent) {
  'main thread';
  e.currentTarget.setStyleProperty('opacity', '0.6');
}

function release(e: MainThread.TouchEvent) {
  'main thread';
  e.currentTarget.setStyleProperty('opacity', '1');
}

export function Button({ className, onTap, children }: ButtonProps) {
  return (
    <view
      className={className}
      main-thread:bindtouchstart={press}
      main-thread:bindtouchend={release}
      main-thread:bindtouchcancel={release}
      bindtap={onTap}
    >
      {children}
    </view>
  );
}
