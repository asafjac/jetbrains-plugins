import { FC } from "../react";

export type BazProps = { amount: number };

/** Reached as `FooRegistry.qux.Baz` - a nested slot, two hops from the consumer. */
export const Baz: FC<BazProps> = (props) => ({ type: "Baz", props });
