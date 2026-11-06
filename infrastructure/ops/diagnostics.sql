\set ON_ERROR_STOP on
BEGIN READ ONLY;
SELECT jsonb_build_object('section','connections','rows',coalesce(jsonb_agg(row_to_json(t)),'[]'::jsonb))
FROM (SELECT datname,usename,application_name,state,wait_event_type,wait_event,count(*) AS sessions,
 max(extract(epoch FROM clock_timestamp()-xact_start)) AS oldest_transaction_seconds
 FROM pg_stat_activity WHERE backend_type='client backend' GROUP BY 1,2,3,4,5,6 ORDER BY 1,2) t;
SELECT jsonb_build_object('section','locks','rows',coalesce(jsonb_agg(row_to_json(t)),'[]'::jsonb))
FROM (SELECT d.datname,l.locktype,l.mode,l.granted,count(*) AS locks
 FROM pg_locks l LEFT JOIN pg_database d ON d.oid=l.database GROUP BY 1,2,3,4 ORDER BY 1,2) t;
SELECT jsonb_build_object('section','blocking','rows',coalesce(jsonb_agg(row_to_json(t)),'[]'::jsonb))
FROM (SELECT pid,datname,usename,wait_event,pg_blocking_pids(pid) AS blocking_pids,
 extract(epoch FROM clock_timestamp()-xact_start) AS age_seconds
 FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid))>0 ORDER BY xact_start) t;
SELECT jsonb_build_object('section','vacuum','rows',coalesce(jsonb_agg(row_to_json(t)),'[]'::jsonb))
FROM (SELECT relname,n_live_tup,n_dead_tup,last_autovacuum,last_autoanalyze,autovacuum_count,
 pg_total_relation_size(relid) AS total_bytes FROM pg_stat_user_tables WHERE schemaname='public' ORDER BY n_dead_tup DESC) t;
SELECT jsonb_build_object('section','queries','rows',coalesce(jsonb_agg(row_to_json(t)),'[]'::jsonb))
FROM (SELECT queryid,calls,total_exec_time,mean_exec_time,max_exec_time,rows,shared_blks_hit,shared_blks_read,temp_blks_written
 FROM monitoring.pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database())
 ORDER BY total_exec_time DESC LIMIT 20) t;
SELECT jsonb_build_object('section','wal','rows',coalesce(jsonb_agg(row_to_json(t)),'[]'::jsonb))
FROM (SELECT pg_current_wal_lsn() AS current_lsn,wal_records,wal_bytes,wal_buffers_full,stats_reset FROM pg_stat_wal) t;
SELECT jsonb_build_object('section','archive','rows',coalesce(jsonb_agg(row_to_json(t)),'[]'::jsonb))
FROM (SELECT current_setting('archive_mode') AS archive_mode,archived_count,failed_count,last_archived_time,last_failed_time FROM pg_stat_archiver) t;
SELECT jsonb_build_object('section','settings','rows',coalesce(jsonb_agg(row_to_json(t)),'[]'::jsonb))
FROM (SELECT name,setting,unit FROM pg_settings WHERE name IN ('max_connections','superuser_reserved_connections','shared_preload_libraries',
 'autovacuum','log_min_duration_statement','log_parameter_max_length','log_parameter_max_length_on_error','log_error_verbosity','statement_timeout','lock_timeout','idle_in_transaction_session_timeout') ORDER BY name) t;
COMMIT;
