# Development guide

This guide explains how to extend BoostBuddy TV using its current architecture. [AGENTS.md](../AGENTS.md) contains the repository rules and behavior invariants; [README_TV.md](../README_TV.md) covers toolchains, build commands, installation, and signing.

## Architecture and useful examples

The shared application uses Decompose components for presentation logic, Compose for rendering, Ktor and Kotlinx Serialization for networking, and Kodein for dependency injection. Shared Kotlin code lives under `composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/`; Android integrations and the TV shell live under the equivalent `androidMain` path. Keep platform dependencies outside shared code and preserve existing `expect`/`actual` contracts.

| Start from | What it demonstrates |
| --- | --- |
| [BaseComponent.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/components/BaseComponent.kt) | Lifecycle scope, observable state, and one-time actions. |
| [SettingsComponent.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/components/settings/SettingsComponent.kt) and [SettingsScreen.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/ui/screens/SettingsScreen.kt) | A small component interface, immutable data-class state, event methods, and state-driven rendering. |
| [AuthComponent.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/components/auth/AuthComponent.kt) | Constructor dependencies that tests can replace, asynchronous state transitions, and safe error handling. |
| [RootComponent.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/components/RootComponent.kt) and [RootScreen.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/ui/screens/RootScreen.kt) | Stack/dialog routing, child creation, screen dispatch, and action consumption. |
| [ProfileRepository.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/data/repositories/ProfileRepository.kt) | Response parsing, validation, domain mapping, and `Result` handling. |
| [PlatformDataConfiguration.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/utils/PlatformDataConfiguration.kt) and [Inject.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/data/Inject.kt) | Actual Kodein bindings and dependency access helpers. |

## Components and state

Separate persistent screen state, user input, and one-time effects. Choose the representation that fits the feature: an immutable data class is appropriate for a screen with several simultaneous fields; a sealed hierarchy is useful for mutually exclusive alternatives. Existing components commonly expose named methods such as `onRetryLoginClick()` rather than a separate event class.

`BaseComponent<State, Action>` takes a `ComponentContext` and an initial state. It owns the observable state; subclasses update its protected `viewState`, while the UI observes the public `viewStates`. Do not add a second state holder simply to reproduce a template. Launch ordinary component work in its lifecycle-bound `scope`, preserve coroutine cancellation, and handle stale requests according to the session/generation rules in AGENTS.md.

For one-time UI effects, the current base exposes `viewAction`, `viewActions()`, and `onActionInvoked()`. Components whose interface implements `AppComponent<Action>` can use the `observeAction` extension, as `RootScreen` does for snackbars. Consume effects explicitly instead of leaving them in persistent state to fire again after recomposition. Use parent callbacks or the existing navigation router when the parent owns the transition.

## Compose screens

Pass a component into the screen, observe `component.viewStates.subscribeAsState()`, render its state, and forward user input to component methods. Keep network requests, business decisions, and navigation ownership out of composable bodies. Local presentation concerns such as a focus requester or scroll position can remain in the UI layer.

Use Material 3 and the existing `AppTheme`. For shared assets, use Compose Multiplatform resources and generated resource accessors rather than Android resource identifiers. Put Android context/services in Android code and use the project's platform abstractions from shared screens.

Design loading, empty, error, retry, and populated states together. Keep key controls identifiable with stable semantics/test tags; [LiveStreamCard.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/ui/screens/blog/LiveStreamCard.kt) is an existing example. Where previews are supported, render them with fake state/components inside the app theme rather than requiring a live account or production DI. For TV changes, also check the actual remote focus behavior; a preview alone does not exercise D-pad navigation.

## Adding a screen or route

1. Define the component contract and state under the feature's `components/` package. Put data access in the API/repository layer and keep the component independently testable.
2. Add the Compose screen under `ui/screens/`, or the Android TV presentation under `androidMain/.../ui/tv/` when it is TV-specific. Reuse smaller content composables where both presentations need the same data.
3. Add a serializable route to the navigation owner. For a root route, update `RootComponentImpl.Config`, the matching `RootComponent.Child` type, and the `child(...)` factory. Pass the child `ComponentContext` and required route parameters into the new component. Dialogs use the separate `DialogConfig`/`DialogChild` slot.
4. Add rendering to the corresponding dispatcher. Root screens are selected by `Children` in `RootScreen`; the Android entry point supplies TV-specific `mainContent` and `blogContent`. Check both the route factory and its renderer rather than updating only the route model.
5. Wire the initiating event through the owning component or existing `NavigationRouter`. Verify Back, parameter delivery, restoration, component cleanup, and TV focus/scroll return. Navigation configuration alone does not preserve every piece of transient UI state.

## API calls and dependency injection

Add verified request/response models with Kotlinx Serialization and place endpoint construction in the existing API layer. Keep DTO-to-domain mapping explicit and validate required fields; a nullable or absent field should not silently grant access or invent a successful state. Use Ktor request parameters and body serializers rather than assembling query strings or JSON by hand.

Repositories receive configured clients or API objects through their constructors. Most shared repository calls use `fetchOrError` from [Response.kt](../composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/utils/Response.kt), which returns `Result` and rethrows `CancellationException`. Preserve that behavior in any custom error wrapper. Present safe error messages; raw HTTP exceptions can contain credentials or signed URLs.

Register dependencies in `PlatformDataConfiguration`, choosing `bindSingleton` or `bindProvider` according to ownership. Components currently use `Inject.lazy<T>()`, or constructor parameters with `Inject.instance()` defaults when direct substitution helps tests. `BaseComponent` does not inject an HTTP client automatically. Reuse the configured clients and the existing `SessionTokenManager` flow; adding another auth mechanism can break refresh coordination and session isolation.

Test new mapping and request behavior with synthetic responses or the existing mock HTTP approach. Keep live account checks separate and report them only when actually performed. The detailed authentication, TLS, pagination, and playback constraints remain in AGENTS.md and [CODE_REVIEW_TV.md](CODE_REVIEW_TV.md).

## Review and delivery

Before completing a change, review its behavior and the complete diff. Check state ownership, cancellation/lifecycle handling, dependency lifetime, route creation/rendering, error recovery, and remote usability. Use independent review when useful, focusing on observable behavior, failure paths, and maintainability.

Run focused regression tests for substantive behavior changes, then the Android APK-and-test gate documented in AGENTS.md. Documentation-only edits need link/command and whitespace checks. Report actual commands and outcomes, distinguish source review from runtime verification, and do not infer iOS or live playback coverage from Android unit tests.

Before committing, inspect both the working changes and the selected staged changes:

```sh
git diff
git diff --cached
git diff --check
git diff --cached --check
```

Look for real credential values, private keys, local machine configuration, and unintended files in the staged diff. A variable named `token` or `password` is not itself a leaked secret. Keep keystores, build/cache outputs, generated files, and local SDK/signing configuration out of commits. Change build infrastructure deliberately when the task requires it, and preserve the upstream license and attribution.
