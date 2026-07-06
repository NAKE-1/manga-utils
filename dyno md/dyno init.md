````md
# DYNO Portable Library System
**Version:** 1.0 Draft
**Project:** DYNO
**Purpose:** Portable Manga Library & Synchronization Platform

---

# Vision

DYNO is a portable library format designed specifically for the manga server.

Instead of treating a USB drive as simple storage, every DYNO drive behaves like a portable version of the server. It contains manga, metadata, settings, synchronization information, backups, and a built-in Explorer application that allows anyone to browse the library without installing the server.

The goal is that inserting a DYNO drive into any computer running a compatible manga server immediately makes it recognizable, importable, synchronizable, and browsable.

Think of it as:

- Nintendo game cartridge
- Steam Library
- Plex Library
- Game Save Editor

combined into one portable device.

---

# Core Goals

- Portable manga library
- Offline reading
- Fast synchronization
- Automatic imports
- Automatic exports
- Server cloning
- Library backups
- Visual library browsing
- Incremental synchronization
- Human-readable format
- Cross-platform compatibility
- No manual folder browsing
- Self-contained metadata
- Self-contained Explorer application

---

# Design Philosophy

The user should never need to browse folders.

The USB should feel like plugging a game cartridge into another system.

Opening the drive should launch a graphical application showing:

- Covers
- Manga
- Chapters
- Metadata
- Statistics
- Reading Progress
- Collections
- Storage Information

instead of a list of folders.

---

# Drive Naming

Every compatible drive is labeled

DYNO-001
DYNO-002
DYNO-003
...

The numeric suffix is simply the user-visible identifier.

Internally every drive also has a UUID.

Example

Label

DYNO-001

UUID

5fddde2b-f7e0-4ea5-9eaa-xxxxxxxxxxxx

The UUID never changes.

The drive label may be renamed.

---

# Folder Structure

```
DYNO-001/

.dyno/
    manifest.json
    settings.json
    state.json
    sync.db
    hashes.db
    logs/

Library/

Metadata/

Database/

Covers/

Thumbnails/

Collections/

Explorer/

Backups/

Temp/

README.txt
```

Every folder has a defined purpose.

---

# .dyno Folder

Contains all internal DYNO information.

Nothing here is intended to be manually edited.

Contains

Manifest

Settings

Drive State

Synchronization Database

Checksums

Logs

Version Information

Compatibility Information

---

# Library Folder

Contains every manga.

Example

```
Library/

One Piece/
    Chapter 001.cbz
    Chapter 002.cbz

Frieren/
    Chapter 001.cbz
```

The exact organization is controlled by server settings.

---

# Metadata Folder

Contains exported metadata.

Examples

Titles

Descriptions

Authors

Artists

Genres

Alternative Names

Tags

Reading Direction

Languages

Publication Status

Publishers

Series UUIDs

Chapter UUIDs

Cover references

Collections

User Ratings

Bookmarks

Reading Progress

---

# Database Folder

Contains exported databases.

Examples

Library Database

Search Database

Statistics Database

Collections Database

Bookmarks

Reading Progress

Depending on export profile this may or may not exist.

---

# Covers Folder

Contains

Series Covers

Volume Covers

Banner Images

Author Images

Cached Artwork

---

# Explorer Folder

Contains the DYNO Explorer application.

Platform folders

```
Explorer/

Windows/

Linux/

macOS/
```

Launching the appropriate executable opens the library.

No installation required.

---

# Manifest

Purpose

Identify the drive.

Contains

Drive UUID

Drive Label

Owner

Creation Date

Manifest Version

Explorer Version

Server Compatibility

Library Version

Capabilities

Filesystem Information

Supported Features

Integrity Information

Manifest Example

```
Drive UUID
Drive Label
Manifest Version
Created Date
Explorer Version
Library Version
Capabilities
Compatible Server Versions
```

The manifest is read-only during normal operation except when exporting.

---

# Settings

Contains configurable options.

Examples

Auto Import

Auto Export

Auto Sync

Compression

Encryption

Thumbnail Generation

Safe Eject

Preferred Export Profile

Preferred Import Mode

Conflict Resolution

Explorer Theme

Explorer Language

---

# State

Contains temporary runtime information.

Examples

Last Connected

Last Sync

Last Import

Last Export

Current Server

Current Explorer Version

Verification Status

Sync Generation

Read Progress Version

Last Mounted

---

# Logs

Stores every operation.

Examples

Drive Connected

Import Started

Import Finished

Export Started

Verification Completed

Errors

Warnings

Safe Removal

---

# Drive Detection

Step 1

USB connected

↓

Read volume label

↓

Does label begin with

DYNO-

If no

Ignore drive.

If yes

Continue.

---

Step 2

Verify

.dyno

exists.

If missing

Offer

Initialize Drive

---

Step 3

Read manifest.

Verify

Manifest Version

Compatibility

Checksum

UUID

Explorer Version

If invalid

Display

Unsupported Drive

If valid

Register drive.

---

# Server Drive Registration

The server stores

Drive UUID

Label

Capacity

Used Space

Free Space

Last Connected

Last Sync

Owner

Health

Current Status

Synchronization Status

Verification Status

The drive remains listed even when disconnected.

---

# Portable Drives Page

The manga server contains a dedicated page.

Settings

↓

Portable Drives

Each drive displays

Name

Capacity

Used

Free

Health

Current Version

Explorer Version

Sync Status

Connection Status

Last Seen

Current Export Profile

Actions

Open

Export

Import

Synchronize

Clone

Backup

Verify

Repair

Rename

View Logs

Open Explorer

Safe Eject

---

# Export Profiles

## Library Only

Exports

Library

Metadata

Covers

Collections

No database.

---

## Full Backup

Exports

Everything

Library

Database

Settings

Metadata

Logs

Collections

Bookmarks

Reading Progress

Explorer

---

## Clone Server

Creates another nearly identical server.

Includes

Library

Metadata

Collections

Database

Settings

Users (optional)

Bookmarks

Reading Progress

Explorer

---

## Offline Reader

Exports

Downloaded Chapters

Metadata

Covers

Explorer

No server configuration.

---

## Selected Manga

User selects any series.

Only those series are exported.

---

# Import Workflow

Insert drive.

↓

Server detects DYNO.

↓

Read manifest.

↓

Compare versions.

↓

Scan library.

↓

Display summary.

New Manga

Updated Manga

Deleted Manga

Changed Metadata

Database Changes

Collections

Bookmarks

↓

User selects

Import

Merge

Replace

Skip

↓

Import executes.

↓

State updated.

---

# Synchronization

Synchronization is incremental.

The server compares

Series UUID

↓

Chapter UUID

↓

Checksums

↓

Versions

↓

Transfer only differences.

Never copy the entire drive unless requested.

---

# Conflict Resolution

If both servers modified data

The server compares changes.

If safe

Merge automatically.

Otherwise prompt

Keep Local

Keep USB

Merge

Duplicate

Cancel

---

# Verification

Verification checks

Manifest

Database

Metadata

Covers

Library

Checksums

Explorer Files

Missing Chapters

Corrupt Archives

Broken Metadata

Filesystem Errors

Results are displayed inside both the server and Explorer.

---

# Safe Removal

Before ejecting

Finish pending writes.

Update

Manifest

State

Logs

Synchronization Information

Flush cache.

Unmount.

Display

Safe To Remove.

---

# DYNO Explorer

Every drive contains a portable graphical application.

Purpose

Allow users to browse the library without manually opening folders.

No installation required.

Runs directly from the USB.

---

# Explorer Home

Displays

Drive Name

Owner

Capacity

Used

Free

Drive Health

Manifest Version

Explorer Version

Server Version

Library Version

Last Sync

Library Size

Total Manga

Total Chapters

Total Covers

Collections

Continue Reading

Recently Added

Storage Graph

Recent Activity

---

# Library View

Large cover grid.

Supports

Search

Sorting

Filtering

Collections

Favorites

Reading

Completed

Recently Added

Downloaded

Tags

Genres

Authors

Artists

Languages

Publishers

Custom Collections

---

# Manga Details

Opening a manga displays

Large Cover

Banner

Title

Alternative Titles

Description

Author

Artist

Genres

Tags

Status

Publication Year

Volumes

Chapters

Reading Direction

Language

Storage Size

Import Date

Modification Date

Series UUID

Verification Status

---

# Chapter List

Displays

Chapter Number

Title

Pages

Archive Size

Read Status

Bookmarks

Verification Status

Modification Date

Import Date

---

# Offline Reader

Built-in reader.

Supports

Fit Width

Fit Height

Zoom

Fullscreen

Bookmarks

Resume Reading

RTL

LTR

Single Page

Double Page

Keyboard Shortcuts

Mouse Navigation

---

# Search

Searches

Titles

Alternative Titles

Descriptions

Authors

Artists

Genres

Tags

Series UUID

Chapter UUID

---

# Statistics

Displays

Total Manga

Total Chapters

Total Pages

Average Chapter Size

Largest Series

Newest Series

Oldest Series

Storage Usage

Top Genres

Top Authors

Reading Statistics

---

# Drive Information

Displays

Drive Label

UUID

Filesystem

Capacity

Used Space

Free Space

Manifest Version

Explorer Version

Server Compatibility

Library Version

Verification Date

Synchronization Status

---

# Logs

Built-in log viewer.

Displays

Imports

Exports

Synchronizations

Verification Results

Warnings

Errors

Repair History

---

# Future Features

Portable Themes

Portable Plugins

Encryption

Compressed Libraries

Multiple Libraries

Wireless Sync

LAN Sync

Cloud Bridge

Snapshot Backups

Version History

Automatic Repair

Read-only Mode

QR Pairing

Portable Database Editor

Portable Metadata Editor

Plugin Marketplace

AI Metadata Generation

Duplicate Detection

Differential Compression

Drive Cloning

Explorer Auto Update

---

# Ultimate Goal

The user should never think of a DYNO drive as "just a USB."

Instead, it should behave like a portable manga server cartridge.

Plug it into any compatible computer and you can:

- Browse the library with covers and metadata.
- Read manga completely offline.
- Search the collection instantly.
- View statistics and drive health.
- Import or synchronize with another server.
- Clone a server.
- Back up a server.
- Transfer only changed content.
- Safely eject knowing the drive is fully synchronized.

The experience should feel polished and intentional, similar to opening a game save editor or a dedicated media library application, where the user interacts with the content itself rather than navigating raw directories and files.
````
