# Full-book intelligence generation

The book workspace can generate chapter resources sequentially instead of stopping after the first chapter.

## Per-chapter outputs

Each chapter tracks three independent completion flags:

- Chapter intelligence: summaries, topics, learning objectives, concept map, and glossary.
- Question bank: target of eight generated questions.
- Flashcards: target of eight generated cards.

The chapter document also records its generation status, retry count, and a bounded error message.

## Resume and retry behavior

- Existing resources are detected before generation so completed outputs are not regenerated.
- Generated questions and flashcards use deterministic document IDs, allowing safe upserts on retry.
- If a provider returns fewer than the requested number of questions or cards, the chapter remains partial. A later resume requests only the remaining count.
- A failure in one chapter is recorded without stopping the remaining chapters.
- The final chapter status is only reported as complete when all three resource groups are complete and the status update is saved.

## Page content

Generation first uses the local extracted-page cache. If it is empty, the app attempts to restore page text from the signed-in user's cloud book cache and repopulates the local cache.

## Verification boundary

CI covers Android unit tests, Debug APK assembly, and Firestore/Storage emulator rules. A live, multi-chapter generation smoke test against a configured AI provider remains a separate acceptance check.
