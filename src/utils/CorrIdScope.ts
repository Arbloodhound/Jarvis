/**
 * CorrIdScope — Thread-local-like correlation ID for tracing
 * operations across the entire brain stack.
 *
 * Usage:
 *   const dispose = CorrIdScope.enter('cortex_abc123');
 *   // ... all DebugLog.push() calls inside here get corrId = 'cortex_abc123'
 *   dispose();
 *
 * Nested scopes append: 'cortex_abc123.react_step7'
 */

let _stack: string[] = [];

export class CorrIdScope {
  /**
   * Enter a new correlation scope. Returns a dispose function.
   * Nest by calling enter() inside an existing scope.
   */
  static enter(id: string): () => void {
    const parent = _stack.length > 0 ? _stack[_stack.length - 1] : '';
    const full = parent ? `${parent}.${id}` : id;
    _stack.push(full);
    return () => {
      const idx = _stack.lastIndexOf(full);
      if (idx >= 0) _stack.splice(idx, 1);
    };
  }

  /** Get the current correlation ID. */
  static current(): string {
    return _stack.length > 0 ? _stack[_stack.length - 1] : '';
  }

  /** Get the root correlation ID (the outermost scope). */
  static root(): string {
    if (_stack.length === 0) return '';
    const root = _stack[0];
    return root.includes('.') ? root.split('.')[0] : root;
  }

  /** Clear all scopes (for testing/reset). */
  static reset(): void {
    _stack = [];
  }
}
