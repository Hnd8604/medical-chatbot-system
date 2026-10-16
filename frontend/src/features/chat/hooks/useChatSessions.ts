import { useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useDebouncedValue } from "@/shared/hooks/useDebouncedValue";
import { chatApi, chatKeys } from "../api";
import type { ChatSession } from "../types";

const SEARCH_DEBOUNCE_MS = 300;

/** The history sidebar: searchable session list with rename and delete. */
export function useChatSessions() {
  const queryClient = useQueryClient();
  const [query, setQuery] = useState("");
  const debouncedQuery = useDebouncedValue(query.trim(), SEARCH_DEBOUNCE_MS);
  const listKey = chatKeys.sessionList(debouncedQuery);

  const list = useQuery({
    queryKey: listKey,
    queryFn: () => chatApi.sessions(debouncedQuery),
    placeholderData: keepPreviousData,
  });

  const rename = useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) => chatApi.rename(id, title),
    // Optimistic, so the new title shows immediately; rolled back if the request fails.
    onMutate: async ({ id, title }) => {
      await queryClient.cancelQueries({ queryKey: listKey });
      const previous = queryClient.getQueryData<ChatSession[]>(listKey);
      queryClient.setQueryData<ChatSession[]>(listKey, (sessions) =>
        sessions?.map((session) => (session.id === id ? { ...session, title } : session)),
      );
      return { previous };
    },
    onError: (_error, _variables, context) => queryClient.setQueryData(listKey, context?.previous),
    onSettled: () => queryClient.invalidateQueries({ queryKey: chatKeys.sessions() }),
  });

  const remove = useMutation({
    mutationFn: chatApi.remove,
    onSuccess: (_result, id) => {
      queryClient.setQueryData<ChatSession[]>(listKey, (sessions) => sessions?.filter((session) => session.id !== id));
      void queryClient.invalidateQueries({ queryKey: chatKeys.sessions() });
    },
  });

  return {
    query,
    setQuery,
    sessions: list.data ?? [],
    // Background refreshes after each answer must not flash the loading text.
    loading: list.isPending || (list.isFetching && list.isPlaceholderData),
    error: list.error ?? rename.error,
    rename: (id: string, title: string) => rename.mutate({ id, title }),
    remove: (id: string) => remove.mutateAsync(id),
    refresh: () => queryClient.invalidateQueries({ queryKey: chatKeys.sessions() }),
  };
}
