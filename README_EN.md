# Regional Health Archive Ingest & Acknowledgement System

> Reads the resident archives and chronic-disease task packets pushed down every day by the regional health archive platform, aligns each message to local fields, proposes identity candidates for records that may refer to the same resident, and returns **one platform-parseable `AA`/`AE`/`AR` acknowledgement per message**. Nothing enters the hospital patient archive until a person confirms it, and next-day follow-up **redelivers only the messages whose acknowledgements never arrived** instead of resending the whole batch.

A resident service on the hospital edge host — **no graphical interface**: ingest one message, write one acknowledgement, record one ledger row; reconcile by batch each afternoon and repair only the gaps.

---

## Follow Us

Scan the QR code to follow our official account for updates and community access:

![Follow Us](qrcode.jpg)