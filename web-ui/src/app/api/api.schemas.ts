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

export const AuthConfigSchema = z.strictObject({
  enabled: z.boolean(),
});
export type AuthConfig = z.infer<typeof AuthConfigSchema>;

export const AccessTokenSchema = z.strictObject({
  access_token: z.string().check(z.minLength(1)),
  token_type: z.literal('Bearer'),
  expires_at: z.iso.datetime(),
});
export type AccessToken = z.infer<typeof AccessTokenSchema>;

export const UserProfileSchema = z.strictObject({ email: z.email() });
export type UserProfile = z.infer<typeof UserProfileSchema>;

export const BrowserSignOutSchema = z.strictObject({ signedOut: z.literal(true) });

export const DeletedMessageSchema = z.strictObject({ status: z.literal('deleted') });
export type DeletedMessage = z.infer<typeof DeletedMessageSchema>;
