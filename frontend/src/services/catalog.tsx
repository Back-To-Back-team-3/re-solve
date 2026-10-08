"use client";
import { createContext, useContext, type ReactNode } from "react";
import { api } from "./mock-api";
import { useResource } from "./hooks";
import { ErrorState, Loading } from "@/components/ui";
type Catalog = Awaited<ReturnType<typeof api.catalog>>;
const Context = createContext<Catalog | null>(null);
export function CatalogProvider({ children }: { children: ReactNode }) {
  const r = useResource(api.catalog);
  if (r.loading)
    return (
      <div className="container">
        <Loading />
      </div>
    );
  if (r.error)
    return (
      <div className="container">
        <ErrorState message={r.error} retry={r.retry} />
      </div>
    );
  return <Context.Provider value={r.data!}>{children}</Context.Provider>;
}
export function useCatalog() {
  const catalog = useContext(Context);
  if (!catalog) throw new Error("CatalogProvider missing");
  return catalog;
}
