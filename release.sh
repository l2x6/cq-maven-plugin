#!/bin/bash
#
# Copyright (c) 2020 CQ Maven Plugin
# project contributors as indicated by the @author tags.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# A script for editting .github/project.yml file, committing the changes and pushing the brach to upstream

set -e
upstreamUrl="git@github.com:l2x6/cq-maven-plugin.git"

if [ "$#" -eq  "2" ]; then
    releaseVersion="$1"
    array=($(echo "$releaseVersion" | tr . '\n'))
    releaseVersionMajorMinor="$((array[0])).$((array[1]))"
    nextVersion="$2"
elif [ "$#" -eq  "1" ]; then
    releaseVersion="$1"
    array=($(echo "$releaseVersion" | tr . '\n'))
    releaseVersionMajorMinor="$((array[0])).$((array[1]))"
    array[2]=$((array[2]+1))
    nextVersion="$(IFS=. ; echo "${array[*]}")-SNAPSHOT"
    echo "Setting default nextVersion ${nextVersion}"
else
    echo "One or two params expected: $0 <release-version>[ <next-development-version>]"
fi

set -x
topicBranch=trigger-release-$releaseVersion
git checkout -b $topicBranch

sed -i -e 's|  current-version:.*|  current-version: '$releaseVersion'|' .github/project.yml
sed -i -e 's|  next-version:.*|  next-version: '$nextVersion'|' .github/project.yml
sed -i -e 's|  current-major-minor-version:.*|  current-major-minor-version: '$releaseVersionMajorMinor'|' .github/project.yml

git add -A
git commit -m "Trigger release $releaseVersion"
git push "${upstreamUrl}" $topicBranch
