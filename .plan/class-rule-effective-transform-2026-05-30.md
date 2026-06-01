# ClassRule Effective Transform Selection - 2026-05-30

## Evidence

- User observation: `ClassRule` currently does not affect obfuscation behavior.
- Source path: `ConfigParser.buildConfig` parses `rules` into `ClassRule`
  records and stores them on `ObfuscationConfig`.
- Source path: `ObfuscationPipeline.execute` schedules passes only through
  `config.isTransformEnabled(pass.id())`.
- Source path: `ObfuscationConfig.isTransformEnabled` reads only the top-level
  `transforms` map.
- Documentation path: `docs/CONFIG.md` and `docs/zh-CN/CONFIG.md` state that
  rules are parsed but pipeline support is limited.
- Exact failing invariant: a configured `ClassRule` has no runtime path into
  pass scheduling, class-level pass execution, method-level pass execution, or
  JVM coverage validation, so it cannot enable, disable, or exclude transforms
  for matching classes.
- Scope boundary: this plan changes only generic ClassRule matching and JVM
  transform selection. It does not change bytecode rewrite algorithms, CFF
  block construction, hidden-key propagation, native/JNI paths, generated
  helper topology, fallback behavior, or JVM ABI rules.

## Plan

- [x] Baseline/evidence capture
  - Scope: identify the exact configuration-to-pipeline path proving
    `ClassRule` is parsed but ignored.
  - Required evidence: source chain from `ConfigParser` to
    `ObfuscationPipeline.execute` and `ObfuscationConfig.isTransformEnabled`.
  - Validation target: source inspection and repository search for `ClassRule`
    usages.
  - Completion criteria: failing invariant is recorded above.

- [x] Plan/write review
  - Scope: record the narrow repair before implementation.
  - Required evidence: plan names the runtime path, excludes bytecode/native
    behavior changes, and records validation targets.
  - Validation target: diff review of this document.
  - Completion criteria: plan exists before code edits.
  - Review note: current harness subagent tools are restricted to explicit
    user-requested delegation, so local review is the available substitute
    unless the user requests a subagent.

- [ ] Repair ClassRule transform resolution
  - Scope: add ordered class-rule matching to the configuration model and use
    it from pipeline scheduling plus per-class/per-method pass execution.
  - Behavior: top-level `transforms` are the default. Rules are evaluated in
    file order against each class; later matching rules override earlier
    matching rules for the same transform ID. Transform entries merge by ID,
    so a rule that mentions only `stringObfuscation` does not change the
    effective `renamer` setting. The last matching rule's `exclude` value is
    authoritative; a final `exclude: true` disables all JVM transforms for the
    class, while a later `exclude: false` re-enables the merged effective
    transform settings.
  - Required evidence: a pass enabled only by a matching rule is scheduled; a
    matching `exclude: true` rule prevents that class from entering normal JVM
    transform execution; unmatched classes keep top-level `transforms`
    behavior.
  - Validation target: focused Gradle test covering rule-only enablement,
    class exclusion, unmatched top-level behavior, and glob matching on both
    dotted and internal class names.
  - Completion criteria: `ClassRule` affects actual pass execution without
    weakening enabled transforms or changing pass internals.

- [ ] Wire scoped global-preparation passes
  - Scope: make global-preparation JVM passes honor the same effective
    per-class transform predicate when they build class/member/key metadata.
  - Behavior: excluded and non-enabled classes remain in the global class graph
    for name reservation, hierarchy, override, and linkage analysis, but they
    are not mutation targets for the scoped transform. ABI-changing transforms
    must not change a target descriptor when a non-enabled caller still reaches
    that original descriptor.
  - Required evidence: renamer and key-dispatch preparation do not transform
    excluded or non-enabled classes when invoked from a rule-enabled pass.
  - Validation target: focused Gradle test that proves excluded classes keep
    their original class names while matching classes are transformed.
  - Completion criteria: globally prepared pass data follows the same
    ClassRule decision used by the pipeline loop.

- [ ] Repair scoped renamer main-entry verification
  - Scope: restrict mandatory main-owner rename verification to classes that
    are actually selected as renamer mutation targets.
  - Required evidence: an executable jar can leave its manifest main owner
    unmatched or excluded while another class is rule-renamed, and the output
    still runs with remapped references.
  - Validation target:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ClassRuleIntegrationTest`.
  - Completion criteria: an unrenamed main owner no longer fails verification
    unless that owner itself is selected for renaming.

- [ ] Repair keyDispatch invokedynamic inbound boundary
  - Scope: include invokedynamic bootstrap handles and handle bootstrap
    arguments from excluded or non-enabled caller classes in keyDispatch's
    unkeyed inbound target set.
  - Required evidence: an excluded caller using a lambda or method reference to
    an enabled target does not let keyDispatch rewrite the target descriptor.
  - Validation target:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ClassRuleIntegrationTest`.
  - Completion criteria: direct calls and invokedynamic handle calls from
    excluded/non-enabled classes both preserve the reached target descriptor.

- [ ] Update config documentation
  - Scope: replace the "limited support" wording with the implemented
    semantics in English and Chinese config docs.
  - Required evidence: docs specify match syntax, rule ordering, top-level
    default behavior, `exclude` behavior, global-preparation boundaries, and
    inner/anonymous class matching behavior. Inner, local, and anonymous
    classes are matched as independent JVM class entries by their actual
    internal names, including `$` suffixes.
  - Validation target: diff review.
  - Completion criteria: docs match implemented behavior.

- [ ] Final review and commit
  - Scope: review diff for style and scope discipline, run validation, update
    this plan with completion evidence, and commit only the scoped files.
  - Required evidence: `git diff --check`, targeted Gradle result, and scoped
    `git status`.
  - Validation target:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ClassRuleIntegrationTest`.
  - Completion criteria: no unrelated dirty work is included and focused tests
    pass from current sources.
