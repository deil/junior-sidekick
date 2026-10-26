import * as z from 'zod/mini';

export const ReportingPeriodSchema = z.enum(['7', '30']);
export type ReportingPeriod = z.infer<typeof ReportingPeriodSchema>;

export const AgentProfileSchema = z.strictObject({
  name: z.string().check(z.trim(), z.minLength(1)),
  slack: z.nullable(
    z.strictObject({
      username: z.string().check(z.trim(), z.minLength(1)),
      user_id: z.string().check(z.minLength(1)),
      team_id: z.string().check(z.minLength(1)),
    }),
  ),
});
export type AgentProfile = z.infer<typeof AgentProfileSchema>;

const count = z.int().check(z.gte(0));
export const UsageStatsSchema = z.strictObject({
  period_days: z.union([z.literal(7), z.literal(30)]),
  users: count,
  conversations: count,
  channels: count,
  tokens_consumed: count,
});
export type UsageStats = z.infer<typeof UsageStatsSchema>;
