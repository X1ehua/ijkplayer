#! /usr/bin/env bash

REMOTE_REPO=$1
LOCAL_WORKSPACE=$2
REF_REPO=$3

if [ -z $1 -o -z $2 -o -z $3 ]; then
    echo "invalid call pull-repo.sh '$1' '$2' '$3'"
elif [ ! -d $LOCAL_WORKSPACE ]; then
    echo git clone --reference $REF_REPO $REMOTE_REPO $LOCAL_WORKSPACE
    git clone --reference $REF_REPO $REMOTE_REPO $LOCAL_WORKSPACE

    echo cd $LOCAL_WORKSPACE
    cd $LOCAL_WORKSPACE

    echo git repack -a
    git repack -a
else
    echo cd $LOCAL_WORKSPACE
    cd $LOCAL_WORKSPACE

    echo git fetch --all --tags
    git fetch --all --tags
    cd -
fi
