# Image Transform Pipeline

**Scenario.** A media service receives batches of uploaded images and applies a chain of transforms (invert,
brighten, flip...). It runs on a box with limited RAM. Build a concurrent pipeline that is fast, but also
**predictable**: output order matches input order, one bad image doesn't sink the batch, and the total size of images
being processed at once stays under a memory budget.

## Model (package `imaging`)
- `Image(String name, int width, int height, int[] pixels)` grayscale, values 0..255, row-major. Given.
  `sizeBytes()` = `width * height * 4`.
- `Transform` is a functional interface `Image apply(Image in)`. Given.
- `Transforms` has three transforms for you to implement: `invert()` (`255 - v`), `brighten(int delta)` (clamp to
  0..255), `flipHorizontal()` (mirror each row). Transforms must **not mutate** their input (return a new image).
- `Result` is given: `success(Image)` / `failure(String name, String error)`.

## `Pipeline(int workers, long memoryBudgetBytes)`
`List<Result> process(List<Image> inputs, List<Transform> chain)`

1. Apply `chain` in order to every image, using `workers` threads.
2. **Order:** `results.get(i)` corresponds to `inputs.get(i)`, no matter which finished first.
3. **Failure isolation:** if any transform throws for an image, that image's result is
   `Result.failure(name, message)` and every other image is unaffected.
4. **Memory budget:** at no moment may the sum of `sizeBytes()` of images *admitted but not yet finished* exceed
   `memoryBudgetBytes`. An image larger than the whole budget is not processed: its result is a failure with an error
   containing `"exceeds memory budget"`.
5. **FIFO admission:** images are admitted strictly in input order. A small image must not jump ahead of a large one
   that is waiting for memory. (This avoids starving big images.)
6. `process` returns only when every image is done. Invalid constructor args (workers < 1, budget < 1) →
   `IllegalArgumentException`. Shut threads down when finished (or reuse a pool and expose `close()`; tests call
   `process` once per instance).

## Hints
- A **fair** `Semaphore` with one permit per byte (budget ≤ `Integer.MAX_VALUE` is fine here), acquired by the
  submitting thread in input order, gives you both the budget and FIFO admission.
- Release permits in a `finally` block, or one failure leaks memory forever.
- Collect `Future<Result>` in input order and `get()` them in order.
