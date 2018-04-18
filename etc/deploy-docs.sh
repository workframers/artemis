#!/usr/bin/env bash

PROJECT=artemis
VERSION=current

# Boneheaded script to push some stuff to S3 for static hosting
# Relies on our CircleCI AWS perms

BUILD=./target

BUCKET=docs.workframe.com
S3_ROOT=s3://${BUCKET}/${PROJECT}/${VERSION}

LOCAL_CODOX=${BUILD}/doc
if [[ -d ${LOCAL_CODOX} ]]; then
    S3_CODOX=${S3_ROOT}
    echo "Copying codox documentation to ${S3_CODOX}..."
    aws s3 sync --delete ${LOCAL_CODOX} ${S3_CODOX}
fi
