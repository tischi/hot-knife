#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkSeriesAlignSIFT
N_NODES=10

ARGV="\
--lambdaFilter 0.25 \
--lambdaModel 0.1 \
--maxEpsilon 20 \
--n5Input /groups/cosem/cosem/data/LoadID365_ROI4_8x8x8nm/LoadID365_ROI4_8x8x8nm.n5 \
--n5Output /groups/cosem/cosem/saalfelds/LoadID365_ROI4_8x8x8nm/LoadID365_ROI4_8x8x8nm.n5 \
--tmpPath /groups/cosem/cosem/saalfelds/LoadID365_ROI4_8x8x8nm/tmp \
--distance 10 \
--minIntensity -3000 \
--maxIntensity 1000 \
--n5DatasetInput /volumes/raw/ch0 \
--n5DatasetOutput /volumes/raw/ch0 \
--iterations 1000"

TERMINATE=1 RUNTIME='24:00' $FLINTSTONE $N_NODES $JAR $CLASS $ARGV

