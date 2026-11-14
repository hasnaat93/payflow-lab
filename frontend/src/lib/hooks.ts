import { useMutation, useQuery, useQueryClient, type UseQueryOptions } from "@tanstack/react-query";
import { api, type QueryParams } from "./api";

/** GET with the path and params as the cache key. */
export function useApi<T>(path: string, params?: QueryParams, options?: Partial<UseQueryOptions<T>>) {
  return useQuery<T>({
    queryKey: [path, params ?? {}],
    queryFn: () => api.get<T>(path, params),
    ...options,
  });
}

/**
 * Mutation that refreshes everything afterwards. Payments, ledger, settlement and
 * the dashboard are views of the same money, so after any write every cached
 * read is suspect; refetching all of them is simpler and safer than guessing.
 */
export function useApiMutation<TInput, TResult>(run: (input: TInput) => Promise<TResult>, onSuccess?: (result: TResult) => void) {
  const queryClient = useQueryClient();
  return useMutation<TResult, unknown, TInput>({
    mutationFn: run,
    onSuccess: (result) => {
      void queryClient.invalidateQueries();
      onSuccess?.(result);
    },
  });
}
