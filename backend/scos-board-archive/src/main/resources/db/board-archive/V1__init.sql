CREATE TABLE site(id TEXT PRIMARY KEY, live_count INTEGER);
CREATE TABLE board(site TEXT NOT NULL REFERENCES site(id),id TEXT NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,seen_at TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,
checkpoint TEXT,front_time TEXT,front_id TEXT,back_time TEXT,back_id TEXT,back_cursor TEXT,backfill_done INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(site,id));
CREATE TABLE post(site TEXT NOT NULL,id TEXT NOT NULL,board_id TEXT NOT NULL,status TEXT NOT NULL,created_at TEXT NOT NULL,
first_seen_at TEXT NOT NULL,last_seen_at TEXT NOT NULL,has_signal INTEGER NOT NULL DEFAULT 0,signal_updated TEXT,signal_comments INTEGER,
pending_fetch INTEGER NOT NULL DEFAULT 0,fetched_at TEXT,content_at TEXT,projected_hash TEXT,PRIMARY KEY(site,id),FOREIGN KEY(site,board_id) REFERENCES board(site,id));
CREATE TABLE post_board_history(id INTEGER PRIMARY KEY,site TEXT NOT NULL,post_id TEXT NOT NULL,from_board TEXT NOT NULL,to_board TEXT NOT NULL,moved_at TEXT NOT NULL);
CREATE TABLE post_revision(id INTEGER PRIMARY KEY,site TEXT NOT NULL,post_id TEXT NOT NULL,hash TEXT NOT NULL,url TEXT NOT NULL,raw TEXT NOT NULL,
title TEXT NOT NULL,text TEXT NOT NULL,author TEXT NOT NULL,created_at TEXT NOT NULL,attachments TEXT NOT NULL,fetched_at TEXT NOT NULL,FOREIGN KEY(site,post_id) REFERENCES post(site,id));
CREATE INDEX post_revision_post ON post_revision(site,post_id,id);
CREATE TABLE comment(site TEXT NOT NULL,post_id TEXT NOT NULL,id TEXT NOT NULL,status TEXT NOT NULL,hash TEXT NOT NULL,parent_id TEXT,
PRIMARY KEY(site,post_id,id),FOREIGN KEY(site,post_id) REFERENCES post(site,id));
CREATE TABLE comment_revision(id INTEGER PRIMARY KEY,site TEXT NOT NULL,post_id TEXT NOT NULL,comment_id TEXT NOT NULL,
hash TEXT NOT NULL,text TEXT NOT NULL,author TEXT NOT NULL,created_at TEXT NOT NULL,parent_id TEXT,fetched_at TEXT NOT NULL);
CREATE INDEX comment_revision_comment ON comment_revision(site,post_id,comment_id,id);
CREATE TABLE crawl_run(id INTEGER PRIMARY KEY,site TEXT NOT NULL REFERENCES site(id),started_at TEXT NOT NULL,finished INTEGER NOT NULL DEFAULT 0,stats TEXT,requests INTEGER,by_status TEXT,permission TEXT);
CREATE TABLE sweep(id INTEGER PRIMARY KEY,site TEXT NOT NULL REFERENCES site(id),started_at TEXT NOT NULL,closed INTEGER NOT NULL DEFAULT 0,compared INTEGER NOT NULL DEFAULT 0);
CREATE UNIQUE INDEX sweep_open ON sweep(site) WHERE closed=0;
CREATE TABLE sweep_board(sweep_id INTEGER NOT NULL REFERENCES sweep(id),board_id TEXT NOT NULL,cursor TEXT,complete INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(sweep_id,board_id));
CREATE TABLE sweep_seen(sweep_id INTEGER NOT NULL REFERENCES sweep(id),post_id TEXT NOT NULL,PRIMARY KEY(sweep_id,post_id));
CREATE TABLE sweep_checked(sweep_id INTEGER NOT NULL REFERENCES sweep(id),post_id TEXT NOT NULL,PRIMARY KEY(sweep_id,post_id));
