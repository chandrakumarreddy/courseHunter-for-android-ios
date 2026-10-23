# AGENTS.md

You are an expert in JavaScript, Rspeedy, and Lynx application development. You write maintainable, performant, and accessible code.

## Read in Advance

Read docs below in advance to help you understand the library or frameworks this project depends on.

- Lynx: [llms.txt](https://lynxjs.org/next/llms.txt), **REQUIRED**.
  While dealing with a Lynx task, an agent **MUST** read this doc because it is an entry point of all available docs about Lynx.

## Commands

- `bun run dev` - Start the dev server

- `bun run build` - Build the app for production

- `bun run preview` - Preview the production build locally

- `bun run test` - Run the tests with Rstest

## Related Docs

- Rsbuild: <https://rsbuild.rs/llms.txt>

- Rspack: <https://rspack.rs/llms.txt>

## Tools

### Biome

- Run `bun run check` to lint your code
- Run `bun run format` to format your code

## Threading

- All computation runs on the background thread: state, data fetching, parsing, sorting, filtering, formatting, timers, business logic and native-module calls. That's the default for components, hooks, `bind*` handlers and effects.
- The main thread only renders and handles frame-critical input: gestures, scroll-linked effects, animations and press feedback. See `src/components/Button/Button.tsx`.
- Main-thread handlers (`main-thread:bind*` with `"main thread"` as the first statement) must stay trivial: read the event, then call `setStyleProperty` / `setAttribute`. No loops, parsing, formatting or API calls.
- Precompute on the background thread whatever a main-thread handler needs (thresholds, sizes, strings). Pass it in as small, JSON-serializable captured values.
- Keep main-thread state in `useMainThreadRef`. Never mirror it in React state on every frame.
- Cross threads rarely. Don't call `runOnBackground` or `setState` from high-frequency events (scroll, touchmove). Hand off once, at gesture or scroll end.
- Don't pass large objects or lists across threads, and don't capture them in main-thread handlers. Pass IDs or the few fields that are needed.
- Break up long background work, such as big lists or chunked processing, so it doesn't delay how quickly the UI reacts.
