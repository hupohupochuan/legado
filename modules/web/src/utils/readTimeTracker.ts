import API from '@api'
import { createReadTimeTracker } from './readTimeTrackerCore'

export const readTimeTracker = createReadTimeTracker(API.saveReadTime)
