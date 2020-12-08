# -*- coding: utf-8 -*-
"""
Created on Mon Dec  7 17:56:50 2020

@author: Vukan
"""

import numpy as np
import matplotlib.pyplot as plt
#from plfg import *
#import math

plt.rcParams['figure.figsize'] = (15, 5) # set default size of plots
plt.rcParams['image.interpolation'] = 'nearest'
plt.rcParams.update({'font.size': 22})



def PLFG (startingPoint, numOfFrames, numOfChirps, numOfSegments, numOfSamples, slopes, segmentResets, interFrameNumOfSamples = 10):
    
    assert(numOfFrames > 0)
    assert(numOfChirps > 0)
    #assert(len(numOfRepeatedChirps) == numOfChirps)
    assert(len(numOfSegments) == numOfChirps)
    assert(len(numOfSamples) == numOfChirps)
    for i in range(0, numOfChirps):
        assert(len(numOfSamples[i]) == numOfSegments[i])
    assert(len(numOfSamples) == numOfChirps)
    for i in range(0, numOfChirps):
        assert(len(slopes[i]) == numOfSegments[i])
    assert(len(segmentResets) == numOfChirps)
    for i in range(0, numOfChirps):
        assert(len(segmentResets[i]) == numOfSegments[i])
    assert(interFrameNumOfSamples > 0)
    
    output = []
    currentValue = startingPoint
    #output.append(currentValue)
    
    for numOfFramesCounter in range (0, numOfFrames):
        for numOfChirpsCounter in range(0, numOfChirps):
            for numOfSegmentsCounter in range(0, numOfSegments[numOfChirpsCounter]):
                for numOfSamplesCounter in range(0, numOfSamples[numOfChirpsCounter][numOfSegmentsCounter]):
                    if (segmentResets[numOfChirpsCounter][numOfSegmentsCounter] or ((numOfSegmentsCounter == 0) and (numOfSamplesCounter == 0))):
                        currentValue = startingPoint
                    else:
                        currentValue += slopes[numOfChirpsCounter][numOfSegmentsCounter]                
                    output.append(currentValue)
        if (numOfFramesCounter < (numOfFrames - 1)):
            for i in range(0, interFrameNumOfSamples):
                currentValue = startingPoint
                output.append(currentValue)
            
        
    return(output)



startingPoint = 0
numOfFrames = 2
numOfChirps = 2
numOfSegments = [2, 3]
numOfSamples = [[30, 5], [40, 10, 20]] 
slopes = [[5, 0], [10, 0, -8]]
segmentResets = [[False, True], [False, False, False]]
interFrameNumberOfSamples = 50

x = PLFG(startingPoint, numOfFrames, numOfChirps, numOfSegments, numOfSamples, slopes, segmentResets, interFrameNumberOfSamples)

x = np.array(x)
length = len(x)

plt.figure()
plt.plot(range(0, length), x[0:length])
plt.xlabel('x')
plt.ylabel('output')
plt.title('PLFG output')
plt.show()
