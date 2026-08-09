# Contributing to NotEnoughPalette

## Commit Convention

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

perf(palette): inline IndirectStorage.entry() into valueAt
feat(mixin): redirect PalettedContainerFactory to optimized container
fix(palette): handle global palette resize edge case
chore(build): update Gradle wrapper
```

**Scopes:** `palette`, `mixin`, `build`, `docs`

## Code Style

- Java 25, 4-space indentation.
- Match existing patterns — look at similar methods in `OptimizedPalettedContainer.java`.
- No `@ts-ignore` / `@SuppressWarnings("unchecked")` without justifying comment.
- Keep `Storage` interface methods minimal; prefer inline over virtual dispatch on hot paths.

## License

By contributing you agree your code is licensed under MIT.
