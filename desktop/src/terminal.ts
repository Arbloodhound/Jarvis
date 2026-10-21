import type { Interface } from "node:readline";

/**
 * One serialized prompt for the terminal front-ends. Node's readline holds
 * one question at a time — a second question asked while one is pending is
 * ignored and its promise never settles — so a subagent's card raised while
 * `jarvis chat` sat at "you ›" never resolved, and the owner's "y" went to
 * the main agent as the request "y". Two overlapping cards lost one the
 * same way.
 *
 * Here every input line has exactly one taker: the card at the head of the
 * queue when one is showing, otherwise the waiting chat prompt. Cards queue
 * and are shown one at a time, each with its own question; the chat prompt
 * is shown again once the last card is answered. While a run works the
 * chat can listen silently (`ask(q, { silent: true })`): a line typed then,
 * with no card up, goes to it (the chat uses it to interrupt the run), and
 * the prompt itself is only shown by `reveal()`. A line is never held over
 * to answer a later card. Closing the input settles every
 * card and the prompt with null, which callers treat as declined / end of
 * input. A card is never lost and never answered by anything but a line
 * typed while it was showing.
 */
export class TerminalPrompt {
  private readonly cards: Array<{ id: string; text: string; question: string; resolve: (answer: string | null) => void }> = [];
  private waiter: { question: string; silent: boolean; resolve: (line: string | null) => void } | null = null;
  private closedFlag = false;

  constructor(private readonly rl: Interface, private readonly out: NodeJS.WritableStream = process.stdout) {
    rl.on("line", (line: string) => this.onLine(line));
    rl.on("close", () => this.onClose());
  }

  get closed(): boolean {
    return this.closedFlag;
  }

  /**
   * The chat prompt: the next line no card takes. null when the input closed.
   * `silent` waits without showing the prompt (a run is printing) until `reveal()`.
   */
  ask(question: string, opts: { silent?: boolean } = {}): Promise<string | null> {
    if (this.closedFlag) return Promise.resolve(null);
    if (this.waiter) return Promise.reject(new Error("Already waiting for a line."));
    return new Promise((resolve) => {
      this.waiter = { question, silent: !!opts.silent, resolve };
      // With a card up, the prompt waits its turn behind it.
      if (!this.cards.length && !opts.silent) this.show(question);
    });
  }

  /** Show a silent chat prompt (the run it listened through has ended). */
  reveal(): void {
    if (!this.waiter || !this.waiter.silent) return;
    this.waiter.silent = false;
    if (!this.cards.length) this.show(this.waiter.question);
  }

  /** Queue a card: `text` is printed when its turn comes, then `question`; resolves with the line typed for it (null when the input closed). */
  card(id: string, text: string, question: string): Promise<string | null> {
    if (this.closedFlag) return Promise.resolve(null);
    return new Promise((resolve) => {
      this.cards.push({ id, text, question, resolve });
      if (this.cards.length === 1) this.showHead();
    });
  }

  /** Take back a card nobody needs any more (its run stopped). True when it was still open. */
  withdraw(id: string, note: string): boolean {
    const i = this.cards.findIndex((c) => c.id === id);
    if (i < 0) return false;
    const [c] = this.cards.splice(i, 1);
    c!.resolve(null);
    if (i === 0) {
      this.out.write(`\n${note}\n`);
      this.next();
    }
    return true;
  }

  /** Print a line of its own without losing whatever prompt is showing. */
  notice(text: string): void {
    this.out.write(`\n${text}\n`);
    if (this.cards.length) this.show(this.cards[0]!.question);
    else if (this.waiter && !this.waiter.silent) this.show(this.waiter.question);
  }

  private onLine(line: string): void {
    const head = this.cards.shift();
    if (head) {
      head.resolve(line);
      this.next();
      return;
    }
    const w = this.waiter;
    if (w) {
      this.waiter = null;
      w.resolve(line);
    }
  }

  private next(): void {
    if (this.cards.length) this.showHead();
    else if (this.waiter && !this.waiter.silent) this.show(this.waiter.question);
  }

  private showHead(): void {
    const c = this.cards[0]!;
    this.out.write(c.text);
    this.show(c.question);
  }

  private show(question: string): void {
    this.rl.setPrompt(question);
    this.rl.prompt();
  }

  private onClose(): void {
    this.closedFlag = true;
    for (const c of this.cards.splice(0)) c.resolve(null);
    const w = this.waiter;
    this.waiter = null;
    w?.resolve(null);
  }
}
