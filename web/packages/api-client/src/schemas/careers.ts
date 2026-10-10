import { z } from 'zod';

export const JobSchema = z.object({
  id: z.string(),
  title: z.string(),
  team: z.string(),
  location: z.string(),
  type: z.string(),
  pay: z.string(),
  summary: z.string(),
  responsibilities: z.array(z.string()),
  requirements: z.array(z.string())
});
export type Job = z.infer<typeof JobSchema>;

export const JobsSchema = z.object({ jobs: z.array(JobSchema) });

export const WORK_AUTHORIZATION = ['Yes', 'Yes, with sponsorship', 'No'] as const;

export const JobApplicationFormSchema = z.object({
  name: z.string().trim().min(3, 'Enter your full name'),
  email: z.string().trim().pipe(z.email('Enter a valid email')),
  linkedin: z
    .string()
    .trim()
    .refine((v) => v === '' || /^https:\/\/(www\.)?linkedin\.com\//.test(v), 'Use a linkedin.com URL'),
  workAuthorization: z.enum(WORK_AUTHORIZATION, { error: 'Choose one' }),
  resumeFileName: z.string().min(1, 'Attach your résumé').refine((v) => /\.pdf$/i.test(v), 'Upload a PDF'),
  /** Media-service asset id of the uploaded résumé (required by the content service behind the BFF). */
  resumeAssetId: z.string().min(1).optional(),
  coverNote: z.string().max(2000).optional(),
  consent: z.literal(true, { error: 'Please accept the candidate privacy notice' })
});
export type JobApplicationForm = z.infer<typeof JobApplicationFormSchema>;

export const ApplicationReceiptSchema = z.object({ reference: z.string().regex(/^CAR-\d{6}$/), jobId: z.string() });
export type ApplicationReceipt = z.infer<typeof ApplicationReceiptSchema>;
