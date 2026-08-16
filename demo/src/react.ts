/**
 * Just enough of React's surface for the demo to read like real code.
 *
 * Declared locally rather than depended on: the plugin resolves through the PSI, which
 * needs no node_modules, and a demo that requires an install is a demo nobody runs.
 */
export type FC<P> = (props: P) => JsxNode;

export type JsxNode = { type: unknown; props: Record<string, unknown> };

declare global {
    namespace JSX {
        type Element = JsxNode;
        interface IntrinsicElements {
            [tag: string]: Record<string, unknown>;
        }
    }
}
